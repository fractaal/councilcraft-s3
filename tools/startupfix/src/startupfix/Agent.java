package startupfix;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.ProtectionDomain;

/**
 * Startup fixes for a heavily modded NeoForge client. Loaded either with -javaagent, or with no Java argument
 * at all when the jar is in the mods folder (see boot/Bootstrap). Each one replaces or edits a class
 * that mods cannot reach, because it belongs to the JDK or to a library that loads before any mod:
 *
 *  - java.lang.module.Resolver (JDK 21 only): the cubic readability loop. See patch.py.
 *  - Mixin's Bytecode.getOpcodeName(int): scans ASM's Opcodes fields by reflection on every call, once per
 *    instruction of every mixin. Results are kept in an array.
 *  - Roxy: its conversion of Voxy's jar is cached, and its scan of every mod jar is answered from the jars'
 *    tables of contents. See RoxyCache.
 *  - ModLauncher's ClassTransformer: its answers are cached on disk. See TransformCache.
 *  - Drippy's early loading window (only if that add-on is installed): follows window resizes from its first
 *    frame, so a tiling compositor doesn't leave the picture in a corner. See Patches.trackEarlyWindowSize.
 *    The same patch draws the "fractalWARP" banner on that window. See EarlyBanner.
 *
 * Every patch is optional: if a class doesn't look as expected it is left alone and a line is printed.
 * -Dstartupfix.off=resolver,opcodes,roxy,transformcache,earlywindow,earlybanner turns patches off.
 *
 * Tried and removed: letting classes that no mixin targets bypass Mixin's launch plugin, which processes every
 * class under one lock. It was correct in a verification run (68,055 classes skipped, none of them a class
 * Mixin modifies) but made no measurable difference to startup, because parallel mod construction is held
 * up inside the JVM's class definition instead.
 */
public final class Agent implements ClassFileTransformer {
    static final String RESOLVER = "java/lang/module/Resolver";
    static final String BYTECODE = "org/spongepowered/asm/util/Bytecode";
    static final String CLASS_TRANSFORMER = "cpw/mods/modlauncher/ClassTransformer";
    static final String ROXY_READER = "net/rasanovum/roxy/loader/RoxyFabricModReader";
    static final String ROXY_LOCATOR = "net/rasanovum/roxy/loader/RoxyDependencyLocator";
    static final String DRIPPY_EARLY_WINDOW = "de/keksuccino/drippyloadingscreen/earlywindow/window/DrippyEarlyWindowProvider";

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    private TransformCache transformCache;
    private final String off;

    private Agent(String off) {
        this.off = "," + off + ",";
    }

    /** With -javaagent:startupfix.jar */
    public static void premain(String args, Instrumentation inst) {
        start(new Agent(System.getProperty("startupfix.off", "")), inst);
    }

    /** Loaded into a running game by startupfix.boot.Bootstrap; `args` is the config file's "off" list. */
    public static void agentmain(String args, Instrumentation inst) {
        if (System.getProperty("startupfix.active") != null) return;
        Agent agent = new Agent(System.getProperty("startupfix.off", "") + "," + (args == null ? "" : args.replace(" ", "")));
        // A class that is already loaded cannot be given the extra methods these patches add.
        for (Class<?> loaded : inst.getAllLoadedClasses()) {
            String name = loaded.getName().replace('.', '/');
            if (name.equals(BYTECODE) || name.equals(CLASS_TRANSFORMER) || name.equals(ROXY_READER) || name.equals(ROXY_LOCATOR)
                    || name.equals(DRIPPY_EARLY_WINDOW)) log(name + " was loaded before the agent, left alone");
        }
        start(agent, inst);
    }

    private static void start(Agent agent, Instrumentation inst) {
        try {
            if (!agent.off("roxy")) RoxyCache.install();
            if (!agent.off("transformcache")) System.getProperties().put(TransformCache.KEY, agent.transformCache = new TransformCache());
            // no fixed-function OpenGL on macOS
            if (!agent.off("earlybanner") && !MAC && EarlyBanner.wanted()) System.getProperties().put(EarlyBanner.KEY, new EarlyBanner());
            inst.addTransformer(agent, true);
            System.setProperty("startupfix.active", "true");    // Memory Tweaks shows its banner when it sees this
            if (!agent.off("resolver") && Runtime.version().feature() == 21) {
                try {
                    inst.retransformClasses(Class.forName("java.lang.module.Resolver"));
                } catch (Throwable t) {
                    log("resolver left alone: " + t);
                }
            }
        } catch (Throwable t) {
            log("not active: " + t);
        }
    }

    /** Where caches go: next to the jar, or in <game folder>/startupfix/cache when the jar is in the mods folder. */
    static Path cacheDir() throws Exception {
        Path jar = jar();
        Path folder = jar.toAbsolutePath().getParent();
        return folder.getFileName().toString().equals("mods") ? folder.resolveSibling("startupfix").resolve("cache") : folder.resolve("cache");
    }

    static Path jar() throws Exception {
        return Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private boolean off(String name) {
        return off.contains("," + name + ",");
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
        if (name == null) return null;
        TransformCache cache = transformCache;
        if (cache != null) cache.defined(loader, name, bytes);
        try {
            switch (name) {
                case RESOLVER:
                    if (off("resolver") || Runtime.version().feature() != 21) return null;
                    try (InputStream in = Agent.class.getResourceAsStream("/startupfix/Resolver.bin")) {
                        byte[] patched = in.readAllBytes();
                        log("resolver patched");
                        return patched;
                    }
                case BYTECODE:
                    if (off("opcodes")) return null;
                    return report("opcode names", Patches.cacheOpcodeNames(bytes));
                case CLASS_TRANSFORMER:
                    if (off("transformcache")) return null;
                    return report("transform cache", Patches.cacheTransform(bytes));
                case ROXY_READER:
                    if (off("roxy")) return null;
                    return report("roxy conversion cache", Patches.askAgentFirst(bytes, ROXY_READER, "patchJar",
                            "(Ljava/nio/file/Path;Lcom/google/gson/JsonObject;)Ljava/nio/file/Path;", RoxyCache.GET, RoxyCache.PUT));
                case ROXY_LOCATOR:
                    if (off("roxy")) return null;
                    return report("roxy scan", Patches.askAgentFirst(bytes, ROXY_LOCATOR, "containsForgifiedFabricLoader",
                            "(Ljava/util/List;)Z", RoxyCache.SCAN, null));
                case DRIPPY_EARLY_WINDOW:
                    if (off("earlywindow") || MAC) return null;    // untested on macOS, where another mod replaces this renderer
                    return report("early window size", Patches.trackEarlyWindowSize(bytes, DRIPPY_EARLY_WINDOW));
                default:
                    return null;
            }
        } catch (Throwable t) {
            log(name + " left alone: " + t);
            return null;
        }
    }

    private static byte[] report(String what, byte[] patched) {
        log(patched == null ? what + ": class not as expected, left alone" : what + " patched");
        return patched;
    }

    static void log(String message) {
        System.err.println("[startupfix] " + message);
    }
}
