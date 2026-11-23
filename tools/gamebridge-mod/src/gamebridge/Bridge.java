package gamebridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * The socket. It listens on 127.0.0.1 only, on the port in config/gamebridge.properties (written on first start, with
 * a random token, readable by the server's user alone). A client sends one JSON object per line:
 *
 *   {"id": 1, "token": "...", "method": "players", "params": {}}
 *
 * and gets one line back: {"id": 1, "ok": true, "result": ...} or {"id": 1, "ok": false, "error": {"code", "message"}}.
 * "subscribe" turns the connection into a stream of {"event": ...} lines instead (see GameEvents).
 *
 * Everything that touches the game runs on the server thread. A request that the server thread doesn't reach in time is
 * abandoned before it starts, so its reply can say truthfully that nothing happened; one that started but didn't finish
 * in time says its effects are unknown.
 */
public final class Bridge {
    private Bridge() {}

    static final Logger LOG = LogUtils.getLogger();
    static final String VERSION = "0.2.1";
    private static final int MAX_LINE = 1 << 20;

    static volatile MinecraftServer server;
    private static volatile ServerSocket listener;
    private static String token;

    static void start(MinecraftServer s) {
        server = s;
        try {
            Path file = FMLPaths.CONFIGDIR.get().resolve("gamebridge.properties");
            Properties p = new Properties();
            if (Files.exists(file)) {
                try (var in = Files.newInputStream(file)) { p.load(in); }
            } else {
                byte[] random = new byte[24];
                new SecureRandom().nextBytes(random);
                p.setProperty("port", "25585");
                p.setProperty("token", HexFormat.of().formatHex(random));
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                try (var out = Files.newOutputStream(file)) {
                    p.store(out, "Game Bridge: local socket for tools. Keep this file private; anyone with the token controls the server.");
                }
            }
            token = p.getProperty("token", "");
            int port = Integer.parseInt(p.getProperty("port", "25585"));
            if (token.length() < 16) {
                LOG.error("[gamebridge] not started: config/gamebridge.properties has no usable token (need 16+ characters)");
                return;
            }
            // IPv4 loopback by its bytes: getLoopbackAddress() gives ::1 on a JVM that prefers IPv6, and the tools dial 127.0.0.1.
            listener = new ServerSocket(port, 16, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
            Thread t = new Thread(Bridge::acceptLoop, "gamebridge-accept");
            t.setDaemon(true);
            t.start();
            LOG.info("[gamebridge] listening on {} (version {})", listener.getLocalSocketAddress(), VERSION);
        } catch (Exception e) {
            LOG.error("[gamebridge] not started", e);
        }
    }

    static void stop() {
        ServerSocket l = listener;
        listener = null;
        if (l != null) try { l.close(); } catch (IOException ignored) {}
        GameEvents.closeAll();
    }

    private static void acceptLoop() {
        ServerSocket l = listener;
        while (l != null && !l.isClosed()) {
            try {
                Socket socket = l.accept();
                Thread t = new Thread(() -> serve(socket), "gamebridge-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (listener != null) LOG.warn("[gamebridge] accept failed: {}", e.toString());
            }
        }
    }

    private static void serve(Socket socket) {
        try (socket) {
            socket.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            String line;
            while ((line = readLine(in)) != null) {
                if (line.isBlank()) continue;
                JsonObject reply = new JsonObject();
                try {
                    JsonObject req = JsonParser.parseString(line).getAsJsonObject();
                    if (req.has("id")) reply.add("id", req.get("id"));
                    String given = req.has("token") ? req.get("token").getAsString() : "";
                    if (!MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                        fail(reply, "unauthorized", "Missing or wrong token. Nothing was run.");
                    } else {
                        String method = req.has("method") ? req.get("method").getAsString() : "";
                        JsonObject params = req.has("params") && req.get("params").isJsonObject() ? req.getAsJsonObject("params") : new JsonObject();
                        if (method.equals("subscribe")) {
                            GameEvents.stream(socket, out, params);   // returns when the client goes away
                            return;
                        }
                        Methods.handle(method, params, reply);
                    }
                } catch (RuntimeException e) {
                    fail(reply, "bad_request", "Could not read the request as a JSON object: " + e.getMessage());
                }
                out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException ignored) {
            // the client went away
        }
    }

    private static String readLine(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (sb.length() >= MAX_LINE) throw new IOException("request line too long");
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static void fail(JsonObject reply, String code, String message) {
        reply.addProperty("ok", false);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        reply.add("error", error);
    }

    static void ok(JsonObject reply, JsonElement result) {
        reply.addProperty("ok", true);
        reply.add("result", result);
    }

    /** A problem the caller can understand and act on; its message goes back as the error. */
    static final class Refusal extends RuntimeException {
        final String code;
        Refusal(String code, String message) { super(message); this.code = code; }
    }

    /**
     * Runs work on the server thread and fills in the reply. Returns false if the work did not finish, in which case
     * the reply already says whether it never started (nothing happened) or started and may still be running.
     */
    static boolean onServerThread(JsonObject reply, long timeoutMs, Callable<JsonElement> work) {
        MinecraftServer s = server;
        if (s == null || !s.isRunning()) {
            fail(reply, "server_stopped", "The server is not running. Nothing was run.");
            return false;
        }
        AtomicInteger state = new AtomicInteger(0);   // 0 queued, 1 started, 2 abandoned before starting
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        s.execute(() -> {
            if (!state.compareAndSet(0, 1)) return;
            try {
                future.complete(work.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            ok(reply, future.get(timeoutMs, TimeUnit.MILLISECONDS));
            return true;
        } catch (TimeoutException e) {
            if (state.compareAndSet(0, 2)) {
                fail(reply, "server_busy", "The server thread did not reach this request within " + timeoutMs
                        + " ms, so it was cancelled before it started. Nothing was read or changed. The server may be stalled or in a long tick.");
            } else {
                fail(reply, "still_running", "The request started on the server thread but had not finished after " + timeoutMs
                        + " ms. It may still complete. If it changes anything, check the result before trying again.");
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Refusal r) fail(reply, r.code, r.getMessage());
            else {
                LOG.warn("[gamebridge] request failed", cause);
                fail(reply, "failed", cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(reply, "interrupted", "The bridge was interrupted while waiting; whether the request ran is unknown.");
        }
        return false;
    }
}
