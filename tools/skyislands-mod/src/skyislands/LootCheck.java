package skyislands;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Measures what mobs really drop on this server, with every mod, config and datapack in play.
 *
 *   /lootcheck <player> <mob id> <count> [spawner]
 *
 * spawns that many mobs at the player, one after another, kills each with a player attack from that player (so the kill
 * is theirs, with their Luck, gear and Apotheosis world tier), and counts every item the death puts into the world:
 * the mob's own loot, loot bags, loot crates, gems. The items themselves and the experience are thrown away. Mobs spawn
 * as natural spawns, or as spawner-block spawns with "spawner". The tally is written to
 * logs/lootcheck/<time>-<mob>.json and a summary to the server log.
 *
 * It needs a real, logged-in player, because Mob Loot Bags and Apotheosis give nothing for a fake player's kills. The
 * kills are real to every other mod too: they count toward that player's statistics, quests, advancements and loot bag
 * pity, and a mythic bag is announced in chat. So this is for a test server. The command only exists when the server's
 * JVM is started with -Dskyislands.lootcheck=true.
 */
public final class LootCheck {
    private LootCheck() {}

    public static final boolean ENABLED = Boolean.getBoolean("skyislands.lootcheck");

    /** Kills per server tick: enough to finish 20,000 in a few seconds without one long tick. */
    private static final int PER_TICK = 100;

    /** Item components whose value splits an item into separate rows: which crate it is, how pure a gem is. */
    private static final String[] DETAIL = {"ftbquests:loot_crate", "apotheosis:purity"};

    private static final class Run {
        ServerPlayer player;
        EntityType<?> type;
        ResourceLocation typeId;
        boolean spawner;
        int wanted, killed, survived;
        long started = System.currentTimeMillis();
        final Map<String, Long> items = new TreeMap<>();
    }

    private static Run run;
    /** True only while a test mob is being killed: every entity that tries to enter the world then is one of its drops. */
    private static boolean counting;

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lootcheck").requires(s -> s.hasPermission(4))
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("mob", ResourceLocationArgument.id())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 200000))
                                        .executes(c -> start(c, false))
                                        .then(Commands.literal("spawner").executes(c -> start(c, true)))))));
    }

    private static int start(CommandContext<CommandSourceStack> c, boolean spawner) throws CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        if (run != null) {
            source.sendFailure(Component.literal("lootcheck is busy: " + run.killed + "/" + run.wanted + " " + run.typeId));
            return 0;
        }
        ResourceLocation id = ResourceLocationArgument.getId(c, "mob");
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null) {
            source.sendFailure(Component.literal("no such entity type: " + id));
            return 0;
        }
        Run r = new Run();
        r.player = EntityArgument.getPlayer(c, "player");
        r.type = type;
        r.typeId = id;
        r.spawner = spawner;
        r.wanted = IntegerArgumentType.getInteger(c, "count");
        if (!(type.create(r.player.serverLevel()) instanceof Mob)) {
            source.sendFailure(Component.literal(id + " is not a mob"));
            return 0;
        }
        run = r;
        source.sendSuccess(() -> Component.literal("lootcheck started: " + r.wanted + " " + id + (spawner ? " (spawner)" : "")
                + " for " + r.player.getScoreboardName() + ", Luck " + r.player.getLuck()), false);
        return 1;
    }

    public static void onTick(ServerTickEvent.Post event) {
        Run r = run;
        if (r == null) return;
        if (r.player.isRemoved() || r.player.hasDisconnected()) {
            finish(r, "stopped: the player left");
            return;
        }
        for (int i = 0; i < PER_TICK && r.killed + r.survived < r.wanted; i++) killOne(r);
        if (r.killed + r.survived >= r.wanted) finish(r, "done");
    }

    private static void killOne(Run r) {
        ServerLevel level = r.player.serverLevel();
        if (!(r.type.create(level) instanceof Mob mob)) {
            r.survived++;
            return;
        }
        mob.moveTo(r.player.getX(), r.player.getY(), r.player.getZ(), 0.0F, 0.0F);
        EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(mob.blockPosition()),
                r.spawner ? MobSpawnType.SPAWNER : MobSpawnType.NATURAL, null);
        level.addFreshEntityWithPassengers(mob);
        // A jockey's mount or rider comes along with the mob; none of them should outlive the test.
        List<Entity> group = new ArrayList<>();
        mob.getRootVehicle().getPassengersAndSelf().forEach(group::add);
        counting = true;
        try {
            for (int hit = 0; hit < 5 && mob.isAlive(); hit++) {
                mob.invulnerableTime = 0;
                mob.hurt(level.damageSources().playerAttack(r.player), mob.getMaxHealth() * 100.0F + 1000.0F);
            }
        } finally {
            counting = false;
        }
        if (mob.isAlive()) r.survived++;
        else r.killed++;
        group.forEach(Entity::discard);
    }

    /** While a test mob dies, nothing enters the world: items are counted, and they, the experience and any spawn go. */
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!counting || event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof ItemEntity item && run != null) {
            ItemStack stack = item.getItem();
            run.items.merge(key(stack), (long) stack.getCount(), Long::sum);
        }
        event.setCanceled(true);
    }

    private static String key(ItemStack stack) {
        StringBuilder key = new StringBuilder(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        for (String detail : DETAIL) {
            DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.parse(detail));
            Object value = type == null ? null : stack.get(type);
            if (value != null) key.append('[').append(value).append(']');
        }
        return key.toString();
    }

    private static void finish(Run r, String outcome) {
        run = null;
        JsonObject out = new JsonObject();
        out.addProperty("outcome", outcome);
        out.addProperty("mob", r.typeId.toString());
        out.addProperty("spawn", r.spawner ? "spawner" : "natural");
        out.addProperty("player", r.player.getScoreboardName());
        out.addProperty("luck", r.player.getLuck());
        out.addProperty("dimension", r.player.serverLevel().dimension().location().toString());
        out.addProperty("difficulty", r.player.serverLevel().getDifficulty().getKey());
        out.addProperty("kills", r.killed);
        out.addProperty("survived", r.survived);
        out.addProperty("seconds", (System.currentTimeMillis() - r.started) / 1000.0);
        JsonObject items = new JsonObject();
        r.items.forEach(items::addProperty);
        out.add("items", items);
        String file = "?";
        try {
            Path dir = r.player.server.getServerDirectory().resolve("logs").resolve("lootcheck");
            Files.createDirectories(dir);
            Path path = dir.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + "-"
                    + r.typeId.getPath().replace('/', '_') + (r.spawner ? "-spawner" : "") + ".json");
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(out));
            file = path.toString();
        } catch (java.io.IOException e) {
            com.mojang.logging.LogUtils.getLogger().error("[skyislands] lootcheck could not write its report", e);
        }
        com.mojang.logging.LogUtils.getLogger().info("[skyislands] lootcheck {}: {} {} killed, {} survived, {} kinds of item, report {}",
                outcome, r.killed, r.typeId, r.survived, r.items.size(), file);
    }
}
