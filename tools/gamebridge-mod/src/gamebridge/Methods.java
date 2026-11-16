package gamebridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/** What the bridge can do. Each method reads its parameters from a JSON object and returns JSON. */
final class Methods {
    private Methods() {}

    private static final String LIST = "status, players, entities, inventory, attributes, nbt, command, subscribe";
    private static final int READ_TIMEOUT_MS = 8000;

    static void handle(String method, JsonObject p, JsonObject reply) {
        switch (method) {
            case "status" -> Bridge.onServerThread(reply, READ_TIMEOUT_MS, Methods::status);
            case "players" -> Bridge.onServerThread(reply, READ_TIMEOUT_MS, () -> {
                JsonArray out = new JsonArray();
                for (ServerPlayer player : server().getPlayerList().getPlayers()) out.add(describe(player));
                return out;
            });
            case "entities" -> Bridge.onServerThread(reply, READ_TIMEOUT_MS, () -> entities(p));
            case "inventory" -> Bridge.onServerThread(reply, READ_TIMEOUT_MS, () -> inventory(p));
            case "attributes" -> Bridge.onServerThread(reply, READ_TIMEOUT_MS, () -> attributes(p));
            case "nbt" -> Bridge.onServerThread(reply, READ_TIMEOUT_MS, () -> nbt(p));
            case "command" -> command(p, reply);
            default -> Bridge.fail(reply, "unknown_method", "There is no method '" + method + "'. Methods: " + LIST + ".");
        }
    }

    private static MinecraftServer server() {
        return Bridge.server;
    }

    // ---- status ----

    private static JsonElement status() {
        MinecraftServer s = server();
        JsonObject o = new JsonObject();
        double ms = s.getAverageTickTimeNanos() / 1.0e6;
        o.addProperty("bridge_version", Bridge.VERSION);
        o.addProperty("minecraft", s.getServerVersion());
        o.addProperty("players_online", s.getPlayerCount());
        o.addProperty("mean_tick_ms", round(ms));
        o.addProperty("tps", round(Math.min(20.0, 1000.0 / Math.max(ms, 0.001))));
        o.addProperty("ticks_since_start", s.getTickCount());
        JsonArray levels = new JsonArray();
        for (ServerLevel level : s.getAllLevels()) {
            JsonObject l = new JsonObject();
            l.addProperty("dimension", level.dimension().location().toString());
            l.addProperty("loaded_chunks", level.getChunkSource().getLoadedChunksCount());
            int entities = 0;
            for (Entity ignored : level.getAllEntities()) entities++;
            l.addProperty("entities", entities);
            levels.add(l);
        }
        o.add("levels", levels);
        return o;
    }

    // ---- entities ----

    static JsonObject describe(Entity e) {
        JsonObject o = new JsonObject();
        o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        o.addProperty("name", e.getName().getString());
        o.addProperty("uuid", e.getStringUUID());
        o.addProperty("dimension", e.level().dimension().location().toString());
        o.add("pos", pos(e.getX(), e.getY(), e.getZ()));
        if (e instanceof LivingEntity living) {
            o.addProperty("health", round(living.getHealth()));
            o.addProperty("max_health", round(living.getMaxHealth()));
        }
        if (e instanceof ServerPlayer player) {
            o.addProperty("gamemode", player.gameMode.getGameModeForPlayer().getName());
            o.addProperty("food", player.getFoodData().getFoodLevel());
            o.addProperty("xp_level", player.experienceLevel);
            o.addProperty("luck", round(player.getLuck()));
            ItemStack held = player.getMainHandItem();
            if (!held.isEmpty()) o.addProperty("holding", id(held) + " (" + held.getHoverName().getString() + ")");
        }
        if (e instanceof ItemEntity item) o.add("item", item(item.getItem()));
        if (!e.getTags().isEmpty()) {
            JsonArray tags = new JsonArray();
            e.getTags().forEach(tags::add);
            o.add("tags", tags);
        }
        return o;
    }

    /** Entities matching "selector", or within "radius" of "near" [x, y, z] in "dimension". At most "limit" are listed. */
    private static JsonElement entities(JsonObject p) {
        int limit = p.has("limit") ? p.get("limit").getAsInt() : 100;
        List<? extends Entity> found;
        if (p.has("selector")) {
            found = resolve(p.get("selector").getAsString());
        } else if (p.has("near")) {
            JsonArray n = p.getAsJsonArray("near");
            double r = p.has("radius") ? p.get("radius").getAsDouble() : 16.0;
            ServerLevel level = level(p);
            Vec3 c = new Vec3(n.get(0).getAsDouble(), n.get(1).getAsDouble(), n.get(2).getAsDouble());
            found = level.getEntities((Entity) null, new AABB(c.x - r, c.y - r, c.z - r, c.x + r, c.y + r, c.z + r), e -> true);
        } else {
            throw new Bridge.Refusal("bad_params", "entities needs \"selector\" (a player name or @e[...]) or \"near\": [x, y, z] with \"radius\".");
        }
        JsonObject o = new JsonObject();
        JsonArray list = new JsonArray();
        for (int i = 0; i < found.size() && i < limit; i++) list.add(describe(found.get(i)));
        o.addProperty("total", found.size());
        if (found.size() > limit) o.addProperty("note", "Only the first " + limit + " of " + found.size() + " are listed. Pass a larger \"limit\" or a narrower selector.");
        o.add("entities", list);
        return o;
    }

    private static List<? extends Entity> resolve(String selector) {
        try {
            EntitySelector sel = new EntitySelectorParser(new StringReader(selector), true).parse();
            return sel.findEntities(server().createCommandSourceStack());
        } catch (CommandSyntaxException e) {
            throw new Bridge.Refusal("bad_selector", "Could not read '" + selector + "' as a player name, UUID or @ selector: " + e.getMessage());
        }
    }

    private static Entity one(JsonObject p) {
        if (!p.has("target")) throw new Bridge.Refusal("bad_params", "This method needs \"target\": a player name, an entity UUID or a selector that matches one entity.");
        String selector = p.get("target").getAsString();
        List<? extends Entity> found = resolve(selector);
        if (found.isEmpty()) {
            throw new Bridge.Refusal("no_match", "Nothing loaded matches '" + selector + "'. A player has to be online, and an entity has to be in a loaded chunk.");
        }
        if (found.size() > 1) {
            throw new Bridge.Refusal("ambiguous", "'" + selector + "' matches " + found.size() + " entities. Narrow it to one, for example with limit=1 or a UUID.");
        }
        return found.get(0);
    }

    private static ServerLevel level(JsonObject p) {
        String name = p.has("dimension") ? p.get("dimension").getAsString() : "minecraft:overworld";
        ServerLevel level = server().getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(name)));
        if (level == null) throw new Bridge.Refusal("no_dimension", "There is no dimension '" + name + "'.");
        return level;
    }

    // ---- items and inventories ----

    static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** An item as the game itself writes it: id, count, display name and every data component. */
    static JsonObject item(ItemStack stack) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id(stack));
        o.addProperty("count", stack.getCount());
        o.addProperty("name", stack.getHoverName().getString());
        var encoded = ItemStack.CODEC.encodeStart(server().registryAccess().createSerializationContext(JsonOps.INSTANCE), stack);
        encoded.result().ifPresent(j -> {
            if (j.isJsonObject() && j.getAsJsonObject().has("components")) o.add("components", j.getAsJsonObject().get("components"));
        });
        encoded.error().ifPresent(err -> o.addProperty("components_error", err.message()));
        return o;
    }

    private static void slot(JsonArray out, String where, int index, ItemStack stack) {
        if (stack.isEmpty()) return;
        JsonObject o = new JsonObject();
        o.addProperty("where", where);
        o.addProperty("slot", index);
        o.add("item", item(stack));
        out.add(o);
    }

    private static final String[] ARMOR = {"feet", "legs", "chest", "head"};

    /**
     * What a player, a mob or a container block holds. "target" is a player or entity; or give "block": [x, y, z] and
     * optionally "dimension" for a chest, barrel or any block that exposes an item handler.
     */
    private static JsonElement inventory(JsonObject p) {
        JsonObject o = new JsonObject();
        JsonArray slots = new JsonArray();
        if (p.has("block")) {
            JsonArray b = p.getAsJsonArray("block");
            BlockPos pos = new BlockPos(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt());
            ServerLevel level = level(p);
            if (!level.isLoaded(pos)) {
                throw new Bridge.Refusal("not_loaded", "The chunk holding " + pos.toShortString() + " is not loaded, and the bridge does not load chunks to read them. A player nearby or a chunk loader loads it.");
            }
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(level, pos, null, null, null);
            o.addProperty("block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
            if (handler == null) throw new Bridge.Refusal("no_inventory", "The block at " + pos.toShortString() + " (" + o.get("block").getAsString() + ") does not expose an item inventory.");
            o.addProperty("size", handler.getSlots());
            for (int i = 0; i < handler.getSlots(); i++) slot(slots, "block", i, handler.getStackInSlot(i));
        } else {
            Entity e = one(p);
            o.add("of", describe(e));
            if (e instanceof ServerPlayer player) {
                var inv = player.getInventory();
                o.addProperty("selected_hotbar_slot", inv.selected);
                for (int i = 0; i < 36; i++) slot(slots, i < 9 ? "hotbar" : "main", i, inv.getItem(i));
                for (int i = 0; i < 4; i++) slot(slots, "armor." + ARMOR[i], 36 + i, inv.getItem(36 + i));
                slot(slots, "offhand", 40, inv.getItem(40));
                curios(player, slots, o);
                if (p.has("ender_chest") && p.get("ender_chest").getAsBoolean()) {
                    var ender = player.getEnderChestInventory();
                    for (int i = 0; i < ender.getContainerSize(); i++) slot(slots, "ender_chest", i, ender.getItem(i));
                } else {
                    o.addProperty("note", "The ender chest is left out; pass \"ender_chest\": true to include it.");
                }
            } else if (e instanceof LivingEntity living) {
                for (EquipmentSlot s : EquipmentSlot.values()) slot(slots, "equipment." + s.getName(), s.getIndex(), living.getItemBySlot(s));
            } else if (e instanceof ItemEntity item) {
                slot(slots, "item_entity", 0, item.getItem());
            } else {
                throw new Bridge.Refusal("no_inventory", "A " + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()) + " has no inventory the bridge knows how to read. Its raw data is available through nbt.");
            }
        }
        o.add("slots", slots);
        return o;
    }

    /** Curios slots (rings, charms, belts...), read through the Curios API if that mod is installed. */
    private static void curios(ServerPlayer player, JsonArray slots, JsonObject o) {
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object optional = api.getMethod("getCuriosInventory", LivingEntity.class).invoke(null, player);
            if (!(optional instanceof Optional<?> opt) || opt.isEmpty()) return;
            Object handler = opt.get();
            Map<?, ?> curios = (Map<?, ?>) find(handler, "getCurios").invoke(handler);
            for (Map.Entry<?, ?> entry : curios.entrySet()) {
                Object stacks = find(entry.getValue(), "getStacks").invoke(entry.getValue());
                if (!(stacks instanceof IItemHandler items)) continue;
                for (int i = 0; i < items.getSlots(); i++) slot(slots, "curios." + entry.getKey(), i, items.getStackInSlot(i));
            }
        } catch (ClassNotFoundException e) {
            // Curios is not installed
        } catch (ReflectiveOperationException | RuntimeException e) {
            o.addProperty("curios_error", "Curios slots could not be read (" + e + "), so they are missing from this list.");
        }
    }

    private static Method find(Object target, String name) throws NoSuchMethodException {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                try { return i.getMethod(name); } catch (NoSuchMethodException ignored) {}
            }
        }
        Method m = target.getClass().getMethod(name);
        m.setAccessible(true);
        return m;
    }

    // ---- attributes ----

    /** Every attribute of "target" that has modifiers, or just "attribute" (an id such as minecraft:generic.luck). */
    private static JsonElement attributes(JsonObject p) {
        Entity e = one(p);
        if (!(e instanceof LivingEntity living)) throw new Bridge.Refusal("not_living", "Only living entities have attributes; this is a " + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()) + ".");
        String only = p.has("attribute") ? p.get("attribute").getAsString() : null;
        JsonObject o = new JsonObject();
        o.add("of", describe(e));
        JsonArray list = new JsonArray();
        BuiltInRegistries.ATTRIBUTE.holders().forEach(holder -> {
            String name = holder.key().location().toString();
            if (only != null && !only.equals(name)) return;
            AttributeInstance inst = living.getAttribute(holder);
            if (inst == null || (only == null && inst.getModifiers().isEmpty())) return;
            JsonObject a = new JsonObject();
            a.addProperty("id", name);
            a.addProperty("value", inst.getValue());
            a.addProperty("base", inst.getBaseValue());
            JsonArray mods = new JsonArray();
            inst.getModifiers().stream().sorted((x, y) -> x.id().toString().compareTo(y.id().toString())).forEach(m -> {
                JsonObject mod = new JsonObject();
                mod.addProperty("id", m.id().toString());
                mod.addProperty("amount", m.amount());
                mod.addProperty("operation", m.operation().getSerializedName());
                mods.add(mod);
            });
            a.add("modifiers", mods);
            list.add(a);
        });
        if (only != null && list.isEmpty()) throw new Bridge.Refusal("no_attribute", "This entity has no attribute '" + only + "'.");
        if (only == null) o.addProperty("note", "Only attributes with at least one modifier are listed. Ask for one by id to see it regardless.");
        o.add("attributes", list);
        return o;
    }

    // ---- raw data ----

    /**
     * The saved data of "target" (an entity) or of the block entity at "block": [x, y, z], optionally narrowed with a
     * "path" in the same syntax as the /data command. Replies over "max_bytes" (default 200000) are refused with the
     * list of keys, so that a narrower path can be asked for instead.
     */
    private static JsonElement nbt(JsonObject p) {
        CompoundTag root;
        if (p.has("block")) {
            JsonArray b = p.getAsJsonArray("block");
            BlockPos pos = new BlockPos(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt());
            ServerLevel level = level(p);
            if (!level.isLoaded(pos)) throw new Bridge.Refusal("not_loaded", "The chunk holding " + pos.toShortString() + " is not loaded, and the bridge does not load chunks to read them.");
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) throw new Bridge.Refusal("no_block_entity", "The block at " + pos.toShortString() + " (" + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()) + ") stores no data.");
            root = be.saveWithFullMetadata(server().registryAccess());
        } else {
            root = one(p).saveWithoutId(new CompoundTag());
        }
        List<Tag> tags = Collections.singletonList(root);
        if (p.has("path")) {
            String path = p.get("path").getAsString();
            try {
                tags = NbtPathArgument.nbtPath().parse(new StringReader(path)).get(root);
            } catch (CommandSyntaxException e) {
                throw new Bridge.Refusal("no_data", "Nothing at path '" + path + "': " + e.getMessage() + " Top-level keys: " + String.join(", ", root.getAllKeys()) + ".");
            }
        }
        JsonArray out = new JsonArray();
        for (Tag tag : tags) out.add(NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag));
        int max = p.has("max_bytes") ? p.get("max_bytes").getAsInt() : 200_000;
        int size = out.toString().length();
        if (size > max) {
            List<String> keys = new ArrayList<>();
            if (tags.size() == 1 && tags.get(0) instanceof CompoundTag c) {
                for (String k : c.getAllKeys()) keys.add(k + " (" + c.get(k).toString().length() + ")");
            }
            throw new Bridge.Refusal("too_large", "That data is " + size + " characters, over the limit of " + max + ". Ask for a narrower \"path\" or raise \"max_bytes\"."
                    + (keys.isEmpty() ? "" : " Keys here, with sizes: " + String.join(", ", keys) + "."));
        }
        JsonObject o = new JsonObject();
        o.addProperty("matches", out.size());
        o.add("data", out.size() == 1 ? out.get(0) : out);
        return o;
    }

    // ---- commands ----

    private static final class Capture implements CommandSource {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        volatile boolean reported, success;
        volatile int result;

        @Override public void sendSystemMessage(Component message) { lines.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }

    /**
     * Runs "command" as the server, with operator permission, and returns everything it printed. Some commands answer
     * after they return (locate, for one); "wait_ms" (up to 30000) keeps listening for late output, and stops early
     * once some has arrived and 400 ms have passed with no more.
     */
    private static void command(JsonObject p, JsonObject reply) {
        if (!p.has("command")) {
            Bridge.fail(reply, "bad_params", "command needs \"command\": the text of a console command, with or without the leading slash.");
            return;
        }
        String command = p.get("command").getAsString();
        long wait = Math.max(0, Math.min(30_000, p.has("wait_ms") ? p.get("wait_ms").getAsLong() : 0));
        Capture capture = new Capture();
        boolean finished = Bridge.onServerThread(reply, 15_000, () -> {
            Bridge.LOG.info("[gamebridge] command: {}", command);
            MinecraftServer s = server();
            ServerLevel overworld = s.overworld();
            CommandSourceStack source = new CommandSourceStack(capture, Vec3.atLowerCornerOf(overworld.getSharedSpawnPos()), Vec2.ZERO,
                    overworld, 4, "GameBridge", Component.literal("GameBridge"), s, null)
                    .withCallback((success, result) -> {
                        capture.reported = true;
                        capture.success = success;
                        capture.result = result;
                    });
            s.getCommands().performPrefixedCommand(source, command);
            return JsonNull.INSTANCE;
        });
        if (!finished) return;
        // Listen for late output: until "wait" runs out, or until something has arrived and then 400 ms pass quietly.
        long started = System.currentTimeMillis(), lastChange = started;
        int atReturn = capture.lines.size(), seen = atReturn;
        while (System.currentTimeMillis() - started < wait) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            int now = capture.lines.size();
            if (now != seen) { seen = now; lastChange = System.currentTimeMillis(); }
            else if (seen > atReturn && System.currentTimeMillis() - lastChange >= 400) break;
        }
        long waited = wait > 0 ? System.currentTimeMillis() - started : 0;
        JsonObject o = new JsonObject();
        o.addProperty("command", command);
        o.addProperty("success", capture.reported && capture.success);
        if (capture.reported) o.addProperty("result", capture.result);
        JsonArray output = new JsonArray();
        synchronized (capture.lines) { capture.lines.forEach(output::add); }
        o.add("output", output);
        o.addProperty("waited_ms", waited);
        o.addProperty("late_lines", seen - atReturn);
        if (!capture.reported) {
            o.addProperty("note", "The command did not report a result. Either it was rejected (the output says why), or it finishes later than this call waited; pass a longer \"wait_ms\" if its answer is missing.");
        }
        Bridge.ok(reply, o);
    }

    // ---- helpers ----

    static JsonArray pos(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(round(x)); a.add(round(y)); a.add(round(z));
        return a;
    }

    static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
