package skyislands;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.slf4j.Logger;

/**
 * A dead sky whale leaves no corpse here; its killer gets the corpse's loot instead (TechKeeper asked on 2026-11-24,
 * and Ben agreed). Sky Whales 0.1.9 builds a floating ribcage of whale bone, bone and whale meat blocks when a whale
 * dies, and that corpse is the whale's only loot: its entity loot table is empty. Mining it breaks it into single-block
 * Sable sub-levels that nothing ever removes, and piles of those cost the server real main-thread time.
 *
 * skyislands.mixin.SkyWhaleDeathMixin notes the dying whale while the mod builds its corpse, and
 * skyislands.mixin.WhaleCorpseBurstMixin stops the build before the first block is placed and hands the blocks the mod
 * worked out to give(). Each block's own loot table is rolled once, as if it were mined without silk touch, and the
 * items go into the killer's inventory, with what doesn't fit dropped at their feet. Without a player killer they drop
 * where the whale was.
 */
public final class WhaleBurst {
    private WhaleBurst() {}

    private static final Logger LOG = LogUtils.getLogger();

    /** The whale whose corpse is being built right now, or null. Only touched on the server thread. */
    public static LivingEntity dying;

    /**
     * Gives the loot of these corpse blocks (position to block) for this whale. Returns false, having given nothing,
     * if the loot couldn't be rolled, so the caller can build the corpse as before instead.
     */
    public static boolean give(ServerLevel level, LivingEntity whale, Map<BlockPos, BlockState> corpse) {
        ServerPlayer killer = whale.getKillCredit() instanceof ServerPlayer p && !(p instanceof FakePlayer)
                && p.isAlive() && !p.hasDisconnected() ? p : null;
        List<ItemStack> loot = new ArrayList<>();
        try {
            corpse.forEach((pos, state) -> Block.getDrops(state, level, pos, null, killer, ItemStack.EMPTY).forEach(stack -> merge(loot, stack)));
        } catch (RuntimeException e) {
            LOG.error("[skyislands] Sky whale: rolling the corpse's loot failed, so the corpse is built as usual", e);
            return false;
        }
        Map<String, Integer> counts = new TreeMap<>();
        for (ItemStack stack : loot) {
            counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            if (killer != null) ItemHandlerHelper.giveItemToPlayer(killer, stack);
            else level.addFreshEntity(new ItemEntity(level, whale.getX(), whale.getY() + whale.getBbHeight() * 0.5, whale.getZ(), stack));
        }
        LOG.info("[skyislands] Sky whale at {} {} {}: no corpse; the loot of its {} blocks went to {}: {}",
                whale.getBlockX(), whale.getBlockY(), whale.getBlockZ(), corpse.size(),
                killer != null ? killer.getScoreboardName() : "the ground where it died", counts);
        return true;
    }

    /** Adds the stack to the list, topping up matching stacks first, so a full inventory drops a few stacks, not hundreds. */
    private static void merge(List<ItemStack> loot, ItemStack stack) {
        for (ItemStack have : loot) {
            if (stack.isEmpty()) return;
            if (ItemStack.isSameItemSameComponents(have, stack) && have.getCount() < have.getMaxStackSize()) {
                int moved = Math.min(stack.getCount(), have.getMaxStackSize() - have.getCount());
                have.grow(moved);
                stack.shrink(moved);
            }
        }
        if (!stack.isEmpty()) loot.add(stack);
    }
}
