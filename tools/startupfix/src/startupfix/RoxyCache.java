package startupfix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Two shortcuts for Roxy, the mod that loads the Fabric build of Voxy on NeoForge. Both run on every launch,
 * on the main thread, before anything else can start:
 *
 *  - Roxy converts Voxy's jar (remapping every class and writing a new 12 MB jar) each time. The result is
 *    kept in a cache folder next to this agent, keyed by the contents of the mods folder, and copied out on
 *    later launches.
 *  - Roxy looks for a bundled Forgified Fabric Loader by reading every mod jar from start to end. The same
 *    question is answered here from each jar's table of contents.
 *
 * The functions are reached from the patched Roxy classes through the system properties, because Roxy and
 * the agent live in different class loaders. Anything unexpected returns null, and Roxy does it its own way.
 */
final class RoxyCache {
    static final String GET = "startupfix.roxyCacheGet", PUT = "startupfix.roxyCachePut", SCAN = "startupfix.roxyScan";

    private RoxyCache() {}

    static void install() {
        System.getProperties().put(GET, (Function<Object, Object>) RoxyCache::get);
        System.getProperties().put(PUT, (BiFunction<Object, Object, Object>) RoxyCache::put);
        System.getProperties().put(SCAN, (Function<Object, Object>) RoxyCache::hasForgifiedFabricLoader);
    }

    /** A fresh copy of the cached conversion of this jar, or null. Roxy deletes the file it is given on exit. */
    private static Object get(Object original) {
        try {
            Path cached = cacheFile((Path) original);
            if (cached == null || !Files.isRegularFile(cached)) return null;
            Path copy = Files.createTempFile("roxy-patched-cached-", ".jar");
            copy.toFile().deleteOnExit();
            Files.copy(cached, copy, StandardCopyOption.REPLACE_EXISTING);
            Agent.log("roxy: reused the cached conversion of " + ((Path) original).getFileName());
            return copy;
        } catch (Throwable t) {
            Agent.log("roxy cache not used: " + t);
            return null;
        }
    }

    /** Keeps a copy of a conversion Roxy just made. Always returns the path it was given. */
    private static Object put(Object original, Object patched) {
        try {
            Path cached = cacheFile((Path) original);
            if (cached != null && patched != null) {
                Files.createDirectories(cached.getParent());
                try (DirectoryStream<Path> old = Files.newDirectoryStream(cached.getParent(), "roxy-*.jar")) {
                    for (Path p : old) Files.deleteIfExists(p);
                }
                Path tmp = cached.resolveSibling(cached.getFileName() + ".tmp");
                Files.copy((Path) patched, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (Throwable t) {
            Agent.log("roxy cache not written: " + t);
        }
        return patched;
    }

    /** The cache file for this jar given everything else in its folder, since the conversion depends on other mods. */
    private static Path cacheFile(Path original) throws Exception {
        Path agentJar = Agent.jar();
        Path mods = original.toAbsolutePath().getParent();
        if (mods == null || !Files.isRegularFile(agentJar)) return null;
        List<String> lines = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(mods)) {
            for (Path f : files) {
                if (Files.isRegularFile(f)) lines.add(f.getFileName() + "|" + Files.size(f) + "|" + Files.getLastModifiedTime(f).toMillis());
            }
        }
        Collections.sort(lines);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(("java " + Runtime.version().feature() + "|" + original.getFileName() + "\n").getBytes(StandardCharsets.UTF_8));
        for (String line : lines) sha.update((line + "\n").getBytes(StandardCharsets.UTF_8));
        return Agent.cacheDir().resolve("roxy-" + HexFormat.of().formatHex(sha.digest(), 0, 12) + ".jar");
    }

    /** Roxy's containsForgifiedFabricLoader, reading names from each jar's central directory. Boolean, or null to decline. */
    private static Object hasForgifiedFabricLoader(Object loadedMods) {
        try {
            for (Object mod : (List<?>) loadedMods) {
                Class<?> api = Class.forName("net.neoforged.neoforgespi.locating.IModFile", false, mod.getClass().getClassLoader());
                Path path = (Path) api.getMethod("getFilePath").invoke(mod);
                if (path == null) continue;
                if (path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("forgified-fabric-loader-")) return Boolean.TRUE;
                if (!Files.isRegularFile(path)) return null;      // a folder mod: let Roxy look itself
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                        String name = e.nextElement().getName();
                        if (name.startsWith("META-INF/jars/forgified-fabric-loader-") && name.endsWith("-full.jar")) return Boolean.TRUE;
                    }
                } catch (IOException unreadable) {
                    // Roxy skips jars it cannot read as well
                }
            }
            return Boolean.FALSE;
        } catch (Throwable t) {
            Agent.log("roxy scan left to Roxy: " + t);
            return null;
        }
    }
}
