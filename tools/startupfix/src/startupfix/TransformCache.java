package startupfix;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.zip.CRC32;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * A disk cache of what ModLauncher's ClassTransformer returns for each class it is asked to load.
 *
 * Every game and mod class goes through that method, where Mixin, access transformers, coremods and the other
 * launch plugins rewrite its bytecode. For a given set of mods the answer is the same on every launch, and
 * for most classes it is "unchanged". This keeps the answers in one file next to the agent and serves them on
 * later launches, so none of that work runs for a class that is in the cache.
 *
 * When the cache is trusted:
 *  - Its file name is a fingerprint of the mods folder, the coremods folder, the Java version, the class
 *    path, the JVM arguments and this agent. Anything else gets a new, empty cache.
 *  - Config files can change which mixins a mod applies, and they are rewritten on every launch, so they
 *    are not fingerprinted. Instead, on every launch Mixin is made to prepare its configurations first, as it
 *    does anyway, and the resulting list of "this mixin applies to that class" is compared with the list
 *    recorded in the cache. Any difference discards the cache before anything is served from it.
 *  - Each entry remembers the length and CRC of the bytes it was computed from and is only used for the same
 *    input.
 *
 * A class that is not in the cache is transformed as usual and added. For that to be safe next to cached
 * classes, Mixin's counter for its generated "Args" classes is moved past the ones in the cache.
 *
 * Mixin generates extra classes for some mixins (inner classes under random names, "Args" classes) and only
 * builds each one when the JVM first asks for it. A cached class refers to its generated classes by name, so
 * they have to be in the cache whenever it is: each time a class is transformed and recorded, every class
 * Mixin has newly registered is generated and written first.
 *
 * MixinExtras generates a few helper classes (LocalIntRefImpl and friends) while mixins are applied and
 * defines them directly, without going through the transformer. Cached classes refer to them, so they are
 * captured when they are defined and kept in the cache too; the class loader then finds them there by name.
 * MixinExtras is told they exist, so it doesn't try to define one a second time.
 *
 * Reached from the patched ClassTransformer through the system properties: apply({transformer, input, name,
 * reason}) answers null (transform it), a byte[] (use this) or TRUE (use the input unchanged);
 * apply({transformer, input, name, reason, output}) records a result.
 */
final class TransformCache implements Function<Object[], Object> {
    static final String KEY = "startupfix.transformCache";
    private static final String MAGIC = "startupfix transform cache 1";
    private static final String ARGS_PREFIX = "org.spongepowered.asm.synthetic.args.Args$";

    private record Entry(boolean changed, int inLen, int inCrc, int pos, int outLen, int outCrc) {}

    private enum Mode { PENDING, RECORD, WARM, OFF }

    private volatile Mode mode = Mode.PENDING;
    private FileChannel lockFile;               // holds transform.lock for as long as this game runs
    private Object owner;                       // the one ClassTransformer this cache is for
    private Thread initThread;
    private Map<String, Entry> index = Map.of();
    private MappedByteBuffer data;
    private DataOutputStream out;
    private long hits, misses, recorded;
    private static final java.util.regex.Pattern MIXINEXTRAS_REF = java.util.regex.Pattern.compile("^(.*)\\.sugar\\.impl\\.ref\\.generated\\.(\\w+)Impl$");
    private final ThreadLocal<String> transforming = new ThreadLocal<>();
    private final Set<String> reportedOutside = ConcurrentHashMap.newKeySet();
    private Class<?> transformingLoader;
    private Method uncached;
    private Map<?, ?> mixinGenerated;           // Mixin's registry of classes it can generate
    private final Set<String> captured = ConcurrentHashMap.newKeySet();
    private final Map<String, byte[]> generated = new ConcurrentHashMap<>();
    private final Object captureLock = new Object();

    @Override
    public Object apply(Object[] a) {
        try {
            String name = (String) a[2];
            if (!"classloading".equals(a[3])) {
                // Mixin asking for a class's bytecode. Only a generated class (one with no bytes of its own) is answered.
                if (a.length != 4 || a[0] != owner || ((byte[]) a[1]).length != 0) return null;
                byte[] made = generated.get(name);
                if (made != null || mode != Mode.WARM) return made;
                Entry e = index.get(name);
                return e != null && e.changed && e.inLen == 0 ? read(e) : null;
            }
            // The loader defines the class right after this returns; remember which one, for defined().
            if (a.length == 5) transforming.set(name);
            Mode m = mode;
            if (m == Mode.PENDING) m = init(a[0]);
            if (m == Mode.OFF || a[0] != owner) return null;
            byte[] in = (byte[]) a[1];
            if (a.length == 4) {
                if (in.length == 0) {
                    byte[] made = generated.get(name);
                    if (made != null) {
                        transforming.set(name);
                        return made;
                    }
                }
                if (m != Mode.WARM) return null;
                Entry e = index.get(name);
                if (e == null || e.inLen != in.length || e.inCrc != crc(in)) { misses++; return null; }
                byte[] cached = e.changed ? read(e) : in;
                if (cached == null) { misses++; return null; }
                hits++;
                transforming.set(name);
                return e.changed ? cached : Boolean.TRUE;
            }
            byte[] result = (byte[]) a[4];
            if (result != in) captureGenerated();
            record(name, in, result);
            return null;
        } catch (Throwable t) {
            mode = Mode.OFF;
            Agent.log("transform cache turned off: " + t);
            return null;
        }
    }

    /** Generates and records every class Mixin has registered since the last call. */
    private void captureGenerated() throws Exception {
        if (mixinGenerated == null || mixinGenerated.size() == captured.size()) return;
        synchronized (captureLock) {
            List<String> fresh = new ArrayList<>();
            for (Object key : new ArrayList<>(mixinGenerated.keySet())) {
                String name = ((String) key).replace('/', '.');
                if (captured.add(name)) fresh.add(name);
            }
            for (String name : fresh) {
                byte[] bytes = (byte[]) uncached.invoke(owner, new byte[0], name, "classloading");
                if (bytes == null || bytes.length == 0) throw new IllegalStateException("Mixin could not generate " + name);
                generated.put(name, bytes);
                record(name, new byte[0], bytes);
            }
        }
    }

    private byte[] read(Entry e) {
        byte[] cached = new byte[e.outLen];
        data.get(e.pos, cached);
        return crc(cached) == e.outCrc ? cached : null;
    }

    /**
     * Called by the agent for every class the JVM defines. A class that turns up in the game's class loader
     * without having just been through the transformer on this thread was defined directly by someone.
     */
    void defined(ClassLoader loader, String internalName, byte[] bytes) {
        if (loader == null || mode == Mode.OFF) return;
        Class<?> tcl = transformingLoader;
        if (tcl == null) {
            if (!loader.getClass().getName().equals("cpw.mods.modlauncher.TransformingClassLoader")) return;
            transformingLoader = tcl = loader.getClass();
        }
        if (loader.getClass() != tcl) return;
        String name = internalName.replace('/', '.');
        if (name.equals(transforming.get()) || name.startsWith("jdk.proxy")) return;
        try {
            if (MIXINEXTRAS_REF.matcher(name).matches()) {
                if (mode == Mode.RECORD || mode == Mode.WARM) record(name, new byte[0], bytes);
            } else if (reportedOutside.size() < 40 && reportedOutside.add(name)) {
                Agent.log("note: " + name + " was defined without the transformer");
            }
        } catch (Throwable t) {
            mode = Mode.OFF;
            Agent.log("transform cache turned off: " + t);
        }
    }

    private synchronized Mode init(Object transformer) throws Exception {
        if (mode != Mode.PENDING) return mode;
        if (initThread == Thread.currentThread()) return Mode.OFF;   // a class loaded while Mixin prepares: transform it live
        initThread = Thread.currentThread();
        owner = transformer;
        long t0 = System.nanoTime();
        // Make Mixin select and prepare its configurations now, exactly where it would on a launch without the
        // cache (at the first class), so every mixin plugin has run before any game class is used.
        uncached = transformer.getClass().getDeclaredMethod("transform$uncached", byte[].class, String.class, String.class);
        uncached.setAccessible(true);
        uncached.invoke(transformer, probeClass(), "startupfix.Probe", "classloading");
        Object mixin = mixinTransformer(transformer);
        String decisions = mixinDecisions(mixin);
        mixinGenerated = (Map<?, ?>) field(field(mixin, "syntheticClassRegistry"), "classes");
        Path file = cacheFile();
        Files.createDirectories(file.getParent());
        // One game at a time: a second one running from the same folder leaves the cache alone.
        // The channel is kept in a field: a channel nothing refers to is closed by the garbage collector, which drops the lock.
        lockFile = FileChannel.open(file.resolveSibling("transform.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        if (lockFile.tryLock() == null) {
            lockFile.close();
            Agent.log("transform cache: another game is using it, not used for this one");
            mode = Mode.OFF;
            return Mode.OFF;
        }
        Mode result = Mode.RECORD;
        if (Files.isRegularFile(file) && load(file, decisions)) {
            int maxArgs = -1;
            for (String n : index.keySet()) {
                if (n.startsWith(ARGS_PREFIX)) {
                    try { maxArgs = Math.max(maxArgs, Integer.parseInt(n.substring(ARGS_PREFIX.length()))); } catch (NumberFormatException ignored) { }
                }
            }
            moveArgsCounterPast(mixin, maxArgs);
            tellMixinExtras(transformer);
            result = Mode.WARM;
        } else {
            try (DirectoryStream<Path> old = Files.newDirectoryStream(file.getParent(), "transform-*.bin")) {
                for (Path p : old) {
                    try { Files.deleteIfExists(p); } catch (IOException inUse) { /* another game has it open */ }
                }
            }
            Files.write(file, (MAGIC + "\n" + decisions + "\n").getBytes(StandardCharsets.UTF_8));
        }
        out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file.toFile(), true), 1 << 20));
        Thread flusher = new Thread(this::flushLoop, "startupfix-cache");
        flusher.setDaemon(true);
        flusher.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::flush, "startupfix-cache-close"));
        Agent.log(String.format("transform cache: %s, %d classes known, ready in %d ms (%s)", result == Mode.WARM ? "in use" : "recording",
                index.size(), (System.nanoTime() - t0) / 1_000_000, file.getFileName()));
        System.setProperty("startupfix.cache", result == Mode.WARM ? "warm" : "cold");    // Memory Tweaks reports it on the menu
        mode = result;
        return result;
    }

    /** Reads the cache file. False when it isn't for these mixin decisions or is unreadable. */
    private boolean load(Path file, String decisions) throws IOException {
        Map<String, Entry> entries = new java.util.HashMap<>(1 << 17);
        byte[] header = (MAGIC + "\n" + decisions + "\n").getBytes(StandardCharsets.UTF_8);
        // Scanned from a plain read of the whole file, so a half-written entry left by a killed launch can be
        // cut off before the file is mapped (Windows refuses to shorten a mapped file).
        if (Files.size(file) > Integer.MAX_VALUE) return false;
        ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(file));
        int size = buf.capacity();
        if (size < header.length) return false;
        byte[] found = new byte[header.length];
        buf.get(0, found);
        if (!java.util.Arrays.equals(header, found)) return false;
        int pos = header.length, good = pos;
        while (pos + 4 <= size) {
            int nameLen = buf.getInt(pos);
            if (nameLen <= 0 || nameLen > 4096 || (long) pos + 4 + nameLen + 17 > size) break;
            byte[] nb = new byte[nameLen];
            buf.get(pos + 4, nb);
            int p = pos + 4 + nameLen;
            boolean changed = buf.get(p) != 0;
            int inLen = buf.getInt(p + 1), inCrc = buf.getInt(p + 5), outLen = buf.getInt(p + 9);
            if (outLen < 0 || (long) p + 13 + outLen + 4 > size) break;
            int outCrc = buf.getInt(p + 13 + outLen);
            entries.put(new String(nb, StandardCharsets.UTF_8), new Entry(changed, inLen, inCrc, p + 13, outLen, outCrc));
            pos = p + 13 + outLen + 4;
            good = pos;
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            if (ch.size() != good) ch.truncate(good);
            data = ch.map(FileChannel.MapMode.READ_ONLY, 0, good);
        }
        index = entries;
        return true;
    }

    private synchronized void record(String name, byte[] in, byte[] result) throws IOException {
        if (out == null || result == null || result.length == 0) return;
        boolean changed = result != in;
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        out.writeInt(nb.length);
        out.write(nb);
        out.writeByte(changed ? 1 : 0);
        out.writeInt(in.length);
        out.writeInt(crc(in));
        out.writeInt(changed ? result.length : 0);
        if (changed) out.write(result);
        out.writeInt(changed ? crc(result) : 0);
        recorded++;
    }

    private void flushLoop() {
        long reported = -1;
        while (true) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
            flush();
            long total = hits + misses + recorded;
            if (total != reported && total > 0 && (reported < 0 || total - reported > 20000)) {
                reported = total;
                Agent.log("transform cache: " + hits + " served, " + misses + " not in cache, " + recorded + " added");
            }
        }
    }

    private synchronized void flush() {
        try {
            if (out != null) out.flush();
        } catch (IOException e) {
            out = null;
        }
    }

    private static int crc(byte[] b) {
        CRC32 c = new CRC32();
        c.update(b);
        return (int) c.getValue();
    }

    /** One cache per combination of mods, Java, class path, JVM arguments and agent build. */
    private static Path cacheFile() throws Exception {
        Path agentJar = Agent.jar();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        List<String> lines = new ArrayList<>();
        for (String dir : new String[]{"mods", "coremods"}) {
            Path d = Path.of(dir);
            if (!Files.isDirectory(d)) continue;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(d)) {
                for (Path f : files) {
                    if (Files.isRegularFile(f)) lines.add(dir + "/" + f.getFileName() + "|" + Files.size(f) + "|" + Files.getLastModifiedTime(f).toMillis());
                }
            }
        }
        Collections.sort(lines);
        lines.add("agent|" + Files.size(agentJar) + "|" + Files.getLastModifiedTime(agentJar).toMillis());
        lines.add("java|" + System.getProperty("java.vm.vendor") + "|" + System.getProperty("java.vm.version"));
        lines.add("classpath|" + System.getProperty("java.class.path"));
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            // Profiling and logging switches don't change what classes look like.
            if (arg.startsWith("-Dstartupfix.") || arg.startsWith("-agentpath") || arg.startsWith("-Xlog") || arg.startsWith("-XX:StartFlightRecording") || arg.startsWith("-XX:FlightRecorderOptions")) continue;
            lines.add("arg|" + arg);
        }
        for (String line : lines) sha.update((line + "\n").getBytes(StandardCharsets.UTF_8));
        return Agent.cacheDir().resolve("transform-" + HexFormat.of().formatHex(sha.digest(), 0, 12) + ".bin");
    }

    private static byte[] probeClass() {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, "startupfix/Probe", null, "java/lang/Object", null);
        w.visitEnd();
        return w.toByteArray();
    }

    /** Mixin's transformer object, found through ModLauncher's launch plugins. */
    private static Object mixinTransformer(Object classTransformer) throws Exception {
        Map<?, ?> plugins = (Map<?, ?>) field(field(classTransformer, "pluginHandler"), "plugins");
        Object plugin = plugins.get("mixin");
        if (plugin == null) throw new IllegalStateException("no mixin launch plugin");
        for (Object p : (List<?>) field(plugin, "processors")) {
            if (p.getClass().getName().endsWith(".MixinTransformationHandler")) {
                Object transformer = field(p, "transformer");
                if (transformer == null) throw new IllegalStateException("Mixin has no transformer yet");
                return transformer;
            }
        }
        throw new IllegalStateException("no Mixin transformation handler");
    }

    /** A digest of every "mixin X applies to class Y" that Mixin has decided on, across all configurations. */
    private static String mixinDecisions(Object mixinTransformer) throws Exception {
        Object processor = field(mixinTransformer, "processor");
        if (!((List<?>) field(processor, "pendingConfigs")).isEmpty()) throw new IllegalStateException("Mixin configurations still pending");
        List<String> lines = new ArrayList<>();
        for (Object config : new ArrayList<>((List<?>) field(processor, "configs"))) {
            String configName = String.valueOf(call(config, "getName"));
            Map<?, ?> mapping = (Map<?, ?>) field(config, "mixinMapping");
            for (Map.Entry<?, ?> e : mapping.entrySet()) {
                for (Object info : (List<?>) e.getValue()) {
                    lines.add(configName + "|" + e.getKey() + "|" + call(info, "getClassName") + "|" + call(info, "getPriority"));
                }
            }
        }
        if (lines.isEmpty()) throw new IllegalStateException("Mixin prepared no mixins");
        Collections.sort(lines);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (String line : lines) sha.update((line + "\n").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(sha.digest()) + " " + lines.size();
    }

    /** Marks the cached LocalRef implementation classes as already generated in each copy of MixinExtras that made one. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void tellMixinExtras(Object transformer) throws Exception {
        ClassLoader loader = (ClassLoader) field(transformer, "transformingClassLoader");
        for (String name : index.keySet()) {
            java.util.regex.Matcher m = MIXINEXTRAS_REF.matcher(name);
            if (!m.matches()) continue;
            Class<?> generator = Class.forName(m.group(1) + ".sugar.impl.ref.LocalRefClassGenerator", true, loader);
            Class<?> refInterface = Class.forName(m.group(1) + ".sugar.ref." + m.group(2), false, loader);
            Field map = generator.getDeclaredField("interfaceToImpl");
            map.setAccessible(true);
            ((Map) map.get(null)).put(refInterface, name.replace('.', '/'));
        }
    }

    /** Mixin numbers its generated Args classes from zero in every launch; start after the cached ones. */
    private static void moveArgsCounterPast(Object mixinTransformer, int max) throws Exception {
        for (Object generator : (List<?>) field(field(mixinTransformer, "extensions"), "generators")) {
            if (generator.getClass().getName().endsWith(".ArgsClassGenerator")) {
                Field next = generator.getClass().getDeclaredField("nextIndex");
                next.setAccessible(true);
                if (next.getInt(generator) <= max) next.setInt(generator, max + 1);
                return;
            }
        }
        if (max >= 0) throw new IllegalStateException("cached Args classes but no Args generator");
    }

    /** Calls a no-argument method on a class the agent cannot name, declared anywhere up its hierarchy. */
    private static Object call(Object owner, String name) throws Exception {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(owner);
            } catch (NoSuchMethodException e) {
                // declared further up
            }
        }
        throw new NoSuchMethodException(owner.getClass().getName() + "." + name);
    }

    private static Object field(Object owner, String name) throws Exception {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException e) {
                // declared further up
            }
        }
        throw new NoSuchFieldException(owner.getClass().getName() + "." + name);
    }
}
