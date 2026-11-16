package skyislands;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/**
 * Loot rules shared by the pack's two per-kill reward systems, Mob Loot Bags and FTB Quests loot crates, so that both
 * mean the same thing by "boss", "a player's kill" and "a spawner mob" (agreed with fractal, 2026-11-16).
 *
 * FTB Quests picks a crate's weight table (passive, monster or boss) with Entity.canUsePortal, which is false for any
 * mob that is not alive, and it rolls crates while the mob is dying. So every mob used the boss table, and with the
 * boss table set to always drop, every death dropped a crate. mixin.FtbEntityWeightMixin replaces that test with
 * weightKind below. FTB Quests also drops crates for any death, whoever or whatever caused it; onDrops applies the
 * rules Mob Loot Bags already has, and gives Apotheosis's gem drops the same spawner rule.
 */
public final class LootRules {
    private LootRules() {}

    /** Mob Loot Bags' own boss list: wither, ender dragon, warden, elder guardian and anything in c:bosses. */
    private static final TagKey<EntityType<?>> BOSSES =
            TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("mob_loot_bags", "bosses"));

    /** Mob Loot Bags puts this tag on mobs from a spawner block (not a trial spawner) when they spawn. */
    private static final String SPAWNER_TAG = "mob_loot_bags.spawner";

    /**
     * The share of their crates and gems that spawner mobs keep, and of their extra bag stacks (LuckBags): the same as
     * spawner_multiplier in Mob Loot Bags' drops.json, so a spawner mob is worth a quarter in every per-kill reward.
     */
    public static final float SPAWNER_SHARE = 0.25F;

    /**
     * The most Luck a player's attribute can add to a loot bag's tier roll. The roll's weights grow exponentially with
     * Luck and the mod has no ceiling, so without this the tier table has no worst case: at the live settings a player
     * at Luck 30 gets about 24 mythic bags per 1000 kills, at 60 about 123.
     */
    public static final float BAG_LUCK_CAP = 30.0F;

    private static final ResourceLocation CRATE = ResourceLocation.fromNamespaceAndPath("ftbquests", "lootcrate");
    private static final ResourceLocation GEM = ResourceLocation.fromNamespaceAndPath("apotheosis", "gem");

    public enum WeightKind { PASSIVE, MONSTER, BOSS }

    /** Which of an FTB Quests crate's three weights applies to this mob. */
    public static WeightKind weightKind(Entity entity) {
        if (entity.getType().is(BOSSES)) return WeightKind.BOSS;
        return entity instanceof Enemy ? WeightKind.MONSTER : WeightKind.PASSIVE;
    }

    public static float capBagLuck(float playerLuck) {
        logOnce(0, "[skyislands] Loot bag tier rolls count a player's Luck up to " + BAG_LUCK_CAP);
        return Math.min(playerLuck, BAG_LUCK_CAP);
    }

    /** The player a kill belongs to, by Mob Loot Bags' rule (DropEngine.creditedPlayer), or null. Never a fake player. */
    public static ServerPlayer creditedPlayer(LivingEntity victim, DamageSource source) {
        Entity attacker = source.getEntity();
        ServerPlayer player = null;
        if (attacker instanceof ServerPlayer p) player = p;
        else if (attacker instanceof OwnableEntity pet && pet.getOwner() instanceof ServerPlayer p) player = p;
        else if (attacker == null && victim.getKillCredit() instanceof ServerPlayer p) player = p;
        return player instanceof FakePlayer ? null : player;
    }

    /**
     * Runs last on a dying mob's drops, after FTB Quests has added its crate, Apotheosis its gems, and the Festive
     * affix its copies. A crate stays only for a real player's kill, and for a spawner mob only a quarter of the time.
     * A spawner mob's Apotheosis gems also stay only a quarter of the time (fractal, 2026-11-16): all of that kill's
     * gems or none, so a Festive burst is kept or dropped as one.
     */
    public static void onDrops(LivingDropsEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        boolean spawner = victim.getTags().contains(SPAWNER_TAG);
        if (event.getDrops().stream().anyMatch(d -> isCrate(d.getItem()))) {
            boolean keep = creditedPlayer(victim, event.getSource()) != null
                    && (!spawner || victim.getRandom().nextFloat() < SPAWNER_SHARE);
            logOnce(1, "[skyislands] Crate drop rules are running (first crate: " + (keep ? "kept" : "removed") + ")");
            if (!keep) event.getDrops().removeIf(d -> isCrate(d.getItem()));
        }
        if (spawner && event.getDrops().stream().anyMatch(d -> isGem(d.getItem()))) {
            boolean keep = victim.getRandom().nextFloat() < SPAWNER_SHARE;
            logOnce(4, "[skyislands] Spawner mobs keep a quarter of their gems (first: " + (keep ? "kept" : "removed") + ")");
            if (!keep) event.getDrops().removeIf(d -> isGem(d.getItem()));
        }
    }

    public static boolean isCrate(ItemStack stack) {
        return CRATE.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    public static boolean isGem(ItemStack stack) {
        return GEM.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static final boolean[] LOGGED = new boolean[5];

    /** One line in the server log the first time each rule runs, so a boot can be checked for rules that never hooked in. */
    public static void logOnce(int rule, String message) {
        if (LOGGED[rule]) return;
        LOGGED[rule] = true;
        com.mojang.logging.LogUtils.getLogger().info(message);
    }
}
