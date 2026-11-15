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
 * rules Mob Loot Bags already has.
 */
public final class LootRules {
    private LootRules() {}

    /** Mob Loot Bags' own boss list: wither, ender dragon, warden, elder guardian and anything in c:bosses. */
    private static final TagKey<EntityType<?>> BOSSES =
            TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("mob_loot_bags", "bosses"));

    /** Mob Loot Bags puts this tag on mobs from a spawner block (not a trial spawner) when they spawn. */
    private static final String SPAWNER_TAG = "mob_loot_bags.spawner";

    /** The share of spawner mobs' crates that are kept: the same as spawner_multiplier in Mob Loot Bags' drops.json. */
    public static final float SPAWNER_CRATE_SHARE = 0.25F;

    /**
     * The most Luck a player's attribute can add to a loot bag's tier roll. The roll's weights grow exponentially with
     * Luck and the mod has no ceiling, so without this the tier table has no worst case: at the live settings a player
     * at Luck 30 gets about 24 mythic bags per 1000 kills, at 60 about 123.
     */
    public static final float BAG_LUCK_CAP = 30.0F;

    private static final ResourceLocation CRATE = ResourceLocation.fromNamespaceAndPath("ftbquests", "lootcrate");

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
     * Runs after FTB Quests has added its crate to a dying mob's drops. The crate stays only for a real player's kill,
     * and for a spawner mob only a quarter of the time.
     */
    public static void onDrops(LivingDropsEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || event.getDrops().stream().noneMatch(d -> isCrate(d.getItem()))) return;
        boolean keep = creditedPlayer(victim, event.getSource()) != null
                && (!victim.getTags().contains(SPAWNER_TAG) || victim.getRandom().nextFloat() < SPAWNER_CRATE_SHARE);
        logOnce(1, "[skyislands] Crate drop rules are running (first crate: " + (keep ? "kept" : "removed") + ")");
        if (!keep) event.getDrops().removeIf(d -> isCrate(d.getItem()));
    }

    public static boolean isCrate(ItemStack stack) {
        return CRATE.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static final boolean[] LOGGED = new boolean[3];

    /** One line in the server log the first time each rule runs, so a boot can be checked for rules that never hooked in. */
    public static void logOnce(int rule, String message) {
        if (LOGGED[rule]) return;
        LOGGED[rule] = true;
        com.mojang.logging.LogUtils.getLogger().info(message);
    }
}
