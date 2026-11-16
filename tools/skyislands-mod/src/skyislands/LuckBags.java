package skyislands;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import tn.naizo.moblootbags.common.content.BagType;
import tn.naizo.moblootbags.common.content.ModTags;
import tn.naizo.moblootbags.common.drop.DropEngine;

/**
 * Luck gives more low-tier loot bags rather than better ones (chosen by fractal on 2026-11-16). Mob Loot Bags
 * still rolls its own bag on every kill, and its drops.json counts only a tenth of a player's Luck toward that bag's
 * tier. On top of that, a hostile kill credited to a real player drops one extra stack below with chance
 * min(CAP, RATE * Luck), a quarter as often from spawner mobs, never from bosses.
 *
 * The bags come from Mob Loot Bags' own createBag and spawnBag, so they get the dimension theme, the drop sound and the
 * player's "found" counter like any other bag, and Apotheosis's Festive affix can't copy them (it only copies the drop
 * list). This class needs Mob Loot Bags, so it is only loaded when that mod is present.
 */
public final class LuckBags {
    private LuckBags() {}

    public static final double RATE = 0.003;
    public static final double CAP = 0.5;

    private record Stack(BagType bag, int weight, int min, int max) {}

    private static final Stack[] STACKS = {
        new Stack(BagType.COMMON, 60, 3, 5),
        new Stack(BagType.UNCOMMON, 28, 2, 3),
        new Stack(BagType.RARE, 10, 1, 2),
        new Stack(BagType.EPIC, 2, 1, 1),
    };
    private static final int TOTAL_WEIGHT = 100;

    public static double chance(LivingEntity victim, ServerPlayer player) {
        double chance = Math.min(CAP, RATE * Math.max(0.0, player.getLuck()));
        return victim.getTags().contains(DropEngine.SPAWNER_TAG) ? chance * LootRules.SPAWNER_SHARE : chance;
    }

    public static void onDrops(LivingDropsEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level) || victim instanceof Player) return;
        if (!(victim instanceof Enemy) && victim.getType().getCategory() != MobCategory.MONSTER) return;
        if (victim.getType().is(ModTags.BOSSES) || victim.getType().is(ModTags.NO_BAGS)) return;
        if (victim.getTags().contains(DropEngine.CURSED_TAG)) return;
        ServerPlayer player = LootRules.creditedPlayer(victim, event.getSource());
        if (player == null) return;
        LootRules.logOnce(3, "[skyislands] Luck bags are running (rate " + RATE + " per Luck, cap " + CAP + ")");
        if (level.getRandom().nextDouble() >= chance(victim, player)) return;
        int r = level.getRandom().nextInt(TOTAL_WEIGHT);
        for (Stack s : STACKS) {
            r -= s.weight;
            if (r < 0) {
                int n = s.min + level.getRandom().nextInt(s.max - s.min + 1);
                for (int i = 0; i < n; i++) {
                    ItemStack bag = DropEngine.createBag(level, s.bag);
                    DropEngine.spawnBag(level, victim, bag, player);
                }
                return;
            }
        }
    }
}
