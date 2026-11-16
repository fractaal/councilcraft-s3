package gamebridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * The live event stream. A connection that sends "subscribe" (optionally with "events": ["chat", "death", ...]) gets
 * one JSON line per event from then on, plus a {"event": "ping"} every 15 seconds so that a dead connection is noticed.
 *
 * Events are queued per connection, 2000 deep. If a slow reader falls further behind than that, the events that did
 * not fit are dropped, and the stream says so with {"event": "dropped", "count": n} before the next event it delivers.
 */
final class GameEvents {
    private GameEvents() {}

    private static final List<String> KINDS = List.of("chat", "join", "leave", "death", "advancement");

    private static final class Subscriber {
        final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(2000);
        final AtomicInteger dropped = new AtomicInteger();
        final Set<String> kinds = new HashSet<>();
        volatile boolean closed;
    }

    private static final List<Subscriber> SUBSCRIBERS = new CopyOnWriteArrayList<>();

    static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, GameEvents::onChat);
        NeoForge.EVENT_BUS.addListener(GameEvents::onJoin);
        NeoForge.EVENT_BUS.addListener(GameEvents::onLeave);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, GameEvents::onDeath);
        NeoForge.EVENT_BUS.addListener(GameEvents::onAdvancement);
    }

    static void stream(Socket socket, OutputStream out, JsonObject params) throws IOException {
        Subscriber sub = new Subscriber();
        if (params.has("events") && params.get("events").isJsonArray()) {
            for (var e : params.getAsJsonArray("events")) sub.kinds.add(e.getAsString());
            for (String k : sub.kinds) {
                if (!KINDS.contains(k)) {
                    JsonObject reply = new JsonObject();
                    Bridge.fail(reply, "bad_params", "There is no event kind '" + k + "'. Kinds: " + String.join(", ", KINDS) + ".");
                    out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
        } else {
            sub.kinds.addAll(KINDS);
        }
        JsonObject hello = new JsonObject();
        JsonObject result = new JsonObject();
        JsonArray kinds = new JsonArray();
        KINDS.stream().filter(sub.kinds::contains).forEach(kinds::add);
        result.add("subscribed", kinds);
        result.addProperty("note", "One JSON line per event from here on. Only events from now are sent; nothing earlier is replayed.");
        Bridge.ok(hello, result);
        out.write((hello + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        SUBSCRIBERS.add(sub);
        try {
            while (!sub.closed && !socket.isClosed()) {
                String line = sub.queue.poll(15, TimeUnit.SECONDS);
                int dropped = sub.dropped.getAndSet(0);
                if (dropped > 0) out.write(("{\"event\":\"dropped\",\"count\":" + dropped + "}\n").getBytes(StandardCharsets.UTF_8));
                out.write(((line == null ? "{\"event\":\"ping\"}" : line) + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            SUBSCRIBERS.remove(sub);
        }
    }

    static void closeAll() {
        for (Subscriber sub : SUBSCRIBERS) {
            sub.closed = true;
            sub.queue.offer("{\"event\":\"server_stopping\"}");
        }
    }

    private static void emit(String kind, JsonObject event) {
        if (SUBSCRIBERS.isEmpty()) return;
        event.addProperty("event", kind);
        event.addProperty("time_ms", System.currentTimeMillis());
        String line = event.toString();
        for (Subscriber sub : SUBSCRIBERS) {
            if (sub.kinds.contains(kind) && !sub.queue.offer(line)) sub.dropped.incrementAndGet();
        }
    }

    private static JsonObject about(ServerPlayer player) {
        JsonObject o = new JsonObject();
        o.addProperty("player", player.getGameProfile().getName());
        o.addProperty("dimension", player.level().dimension().location().toString());
        o.add("pos", Methods.pos(player.getX(), player.getY(), player.getZ()));
        return o;
    }

    private static void onChat(ServerChatEvent event) {
        JsonObject o = about(event.getPlayer());
        o.addProperty("text", event.getRawText());
        emit("chat", o);
    }

    private static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) emit("join", about(player));
    }

    private static void onLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) emit("leave", about(player));
    }

    private static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        JsonObject o = about(player);
        o.addProperty("message", event.getSource().getLocalizedDeathMessage(player).getString());
        Entity killer = event.getSource().getEntity();
        if (killer != null) o.addProperty("killer", killer.getName().getString());
        emit("death", o);
    }

    private static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        event.getAdvancement().value().display().ifPresent(display -> {
            JsonObject o = about(player);
            o.addProperty("id", event.getAdvancement().id().toString());
            o.addProperty("title", display.getTitle().getString());
            o.addProperty("announced", display.shouldAnnounceChat());
            emit("advancement", o);
        });
    }
}
