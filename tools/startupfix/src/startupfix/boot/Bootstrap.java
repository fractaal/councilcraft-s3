package startupfix.boot;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;

/**
 * Turns the startup agent on without any Java argument. NeoForge finds this class through the jar's service
 * file when the jar is in the mods folder, and runs it within the first half second of a client launch, before
 * mods are scanned. It starts a second, short-lived Java process that attaches to this one and loads this same
 * jar as an agent (startupfix.Agent.agentmain). A process may not attach to itself, hence the helper.
 *
 * If anything here fails the game starts as it would without the agent.
 *
 * Opting out: set enabled=false in config/startupfix.properties (written with defaults on the first launch),
 * or list parts to switch off in its "off" line. Nothing happens either when the agent was already loaded
 * with -javaagent.
 */
public final class Bootstrap implements GraphicsBootstrapper {
    @Override
    public String name() {
        return "startupfix";
    }

    @Override
    public void bootstrap(String[] arguments) {
        long start = System.nanoTime();
        try {
            if (System.getProperty("startupfix.active") != null) return;    // loaded with -javaagent
            int target = Arrays.asList(arguments).indexOf("--launchTarget");
            if (target < 0 || target + 1 >= arguments.length || !arguments[target + 1].contains("client")) return;
            Properties config = config();
            if (!Boolean.parseBoolean(config.getProperty("enabled", "true").trim())) {
                log("switched off in config/startupfix.properties");
                return;
            }
            Path jar = jar();
            String java = ProcessHandle.current().info().command().orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            Process helper = new ProcessBuilder(List.of(java, "-Xshare:auto", "-XX:TieredStopAtLevel=1", "-cp", jar.toString(),
                    "startupfix.boot.Attach", Long.toString(ProcessHandle.current().pid()), jar.toString(), config.getProperty("off", "").trim()))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!helper.waitFor(6, TimeUnit.SECONDS)) {
                helper.destroyForcibly();
                log("not active: the attach helper took more than 6 s");
            } else if (System.getProperty("startupfix.active") == null) {
                log("not active: the attach helper ended with status " + helper.exitValue() + " (this Java may not allow attaching)");
            } else {
                log("attached in " + (System.nanoTime() - start) / 1_000_000 + " ms");
            }
        } catch (Throwable t) {
            log("not active: " + t);
        }
    }

    private static Properties config() {
        Properties config = new Properties();
        Path file = Path.of("config", "startupfix.properties");
        try {
            if (Files.exists(file)) {
                try (var in = Files.newBufferedReader(file)) {
                    config.load(in);
                }
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, """
                        # Startup agent (startupfix): makes the game start faster. See the pack README.
                        # enabled=false turns all of it off.
                        enabled=true
                        # Parts to switch off, comma separated: resolver, opcodes, roxy, transformcache, earlywindow, earlybanner
                        off=
                        """);
            }
        } catch (IOException e) {
            log("config/startupfix.properties not usable, using defaults: " + e);
        }
        return config;
    }

    /** This jar's file. NeoForge's class loader reports locations like union:/path/to/the.jar%23123!/ */
    private static Path jar() throws Exception {
        String location = Bootstrap.class.getProtectionDomain().getCodeSource().getLocation().toString();
        if (location.startsWith("union:")) {
            location = "file:" + location.substring("union:".length());
            int end = location.lastIndexOf("%23");
            if (end < 0) end = location.lastIndexOf('#');
            if (end > 0) location = location.substring(0, end);
        }
        if (location.endsWith("!/")) location = location.substring(0, location.length() - 2);
        Path jar = Path.of(new URI(location));
        if (!Files.isRegularFile(jar)) throw new IOException("cannot find this jar from " + location);
        return jar;
    }

    private static void log(String message) {
        System.err.println("[startupfix] " + message);
    }
}
