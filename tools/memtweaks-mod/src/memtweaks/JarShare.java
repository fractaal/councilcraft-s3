package memtweaks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.nio.file.FileSystem;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * Frees the duplicate in-memory copies of jars that are bundled inside other jars.
 *
 * The JDK's zip file system reads a zip that sits inside another zip fully into a byte array. NeoForge opens
 * each bundled jar through up to three layers (its jar-in-jar path layer once, its secure-jar layer twice), and
 * every one gets its own array, so this pack held about 525 MB where 175 MB would do (heap dump, 2026-11-14).
 * Those arrays are never written after they are read, so file systems with identical contents can share one.
 *
 * Everything here reaches into private fields of the JDK and the loader libraries. If any of them has moved,
 * the run stops and reports it; nothing is changed unless two arrays are byte-for-byte equal.
 */
public final class JarShare {
    private JarShare() {}

    public record Result(int inMemoryJars, int shared, long bytesFreed) {}

    /** The one kept copy of each distinct jar, by length and hash, and the set of those arrays for a quick skip. */
    private static final Map<Long, List<byte[]>> KEPT = new HashMap<>();
    private static final Set<byte[]> KEPT_SET = Collections.newSetFromMap(new IdentityHashMap<>());
    private static MethodHandles.Lookup lookup;

    /** Safe to call more than once: later runs only look at file systems opened since the last one. */
    public static synchronized Result run(Iterable<FileSystemProvider> extraProviders) throws Throwable {
        if (lookup == null) lookup = trustedLookup();

        // Every union and jar-in-jar file system is registered in its provider's "fileSystems" map.
        Set<FileSystemProvider> providers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (FileSystemProvider p : FileSystemProvider.installedProviders()) providers.add(p);
        for (FileSystemProvider p : extraProviders) providers.add(p);

        Set<Object> zips = Collections.newSetFromMap(new IdentityHashMap<>());
        for (FileSystemProvider provider : providers) {
            String name = provider.getClass().getName();
            boolean union = name.equals("cpw.mods.niofs.union.UnionFileSystemProvider");
            boolean jarInJar = hasSuperclass(provider.getClass(), "net.neoforged.jarjar.nio.pathfs.PathFileSystemProvider");
            if (!union && !jarInJar) continue;
            Map<?, ?> registered = (Map<?, ?>) get(provider, "fileSystems");
            List<Object> fileSystems;
            synchronized (registered) { fileSystems = new ArrayList<>(registered.values()); }
            for (Object fs : fileSystems) {
                if (union) {
                    Map<?, ?> embedded = (Map<?, ?>) get(fs, "embeddedFileSystems");
                    for (Object meta : new ArrayList<>(embedded.values())) zips.add(get(meta, "fs"));
                } else {
                    Object opened = get(get(fs, "innerSystem"), "value"); // null until first used; don't force it
                    if (opened != null) zips.add(opened);
                }
            }
        }

        int inMemory = 0, shared = 0;
        long freed = 0;
        for (Object zip : zips) {
            if (!zip.getClass().getName().equals("jdk.nio.zipfs.ZipFileSystem")) continue;
            Object channel = get(zip, "ch");
            if (channel == null || !channel.getClass().getName().equals("jdk.nio.zipfs.ByteArrayChannel")) continue;
            inMemory++;
            byte[] bytes = (byte[]) get(channel, "buf");
            if (bytes == null || !(boolean) get(channel, "readonly") || (int) get(channel, "last") != bytes.length) continue;
            if (KEPT_SET.contains(bytes)) continue;

            long key = ((long) bytes.length << 32) | (Arrays.hashCode(bytes) & 0xffffffffL);
            List<byte[]> sameKey = KEPT.computeIfAbsent(key, k -> new ArrayList<>(1));
            byte[] kept = null;
            for (byte[] candidate : sameKey) if (Arrays.equals(candidate, bytes)) { kept = candidate; break; }
            if (kept == null) { sameKey.add(bytes); KEPT_SET.add(bytes); continue; }

            ReadWriteLock lock = (ReadWriteLock) get(channel, "rwlock");
            lock.writeLock().lock();
            try {
                if (get(channel, "buf") == bytes) {
                    setter(channel.getClass(), "buf").invoke(channel, kept);
                    shared++;
                    freed += bytes.length;
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
        return new Result(inMemory, shared, freed);
    }

    private static boolean hasSuperclass(Class<?> c, String name) {
        for (; c != null; c = c.getSuperclass()) if (c.getName().equals(name)) return true;
        return false;
    }

    private static final Map<String, MethodHandle> HANDLES = new HashMap<>();

    private static Object get(Object target, String field) throws Throwable {
        Class<?> c = target.getClass();
        MethodHandle h = HANDLES.get(c.getName() + "#" + field);
        if (h == null) {
            h = lookup.unreflectGetter(findField(c, field));
            HANDLES.put(c.getName() + "#" + field, h);
        }
        return h.invoke(target);
    }

    private static MethodHandle setter(Class<?> c, String field) throws Throwable {
        return lookup.unreflectSetter(findField(c, field));
    }

    private static Field findField(Class<?> c, String field) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { return k.getDeclaredField(field); } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(c.getName() + "." + field);
    }

    /** The JDK's own all-access lookup, read through Unsafe because these packages aren't opened to mods. */
    private static MethodHandles.Lookup trustedLookup() throws ReflectiveOperationException {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Field implLookup = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        return (MethodHandles.Lookup) unsafe.getObject(unsafe.staticFieldBase(implLookup), unsafe.staticFieldOffset(implLookup));
    }
}
