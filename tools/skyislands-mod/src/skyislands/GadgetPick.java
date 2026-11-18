package skyislands;

import com.direwolf20.buildinggadgets2.common.blocks.RenderBlock;
import com.direwolf20.buildinggadgets2.common.items.GadgetBuilding;
import com.direwolf20.buildinggadgets2.common.items.GadgetExchanger;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.direwolf20.buildinggadgets2.util.GadgetUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Building Gadgets 2: the Building and Exchanging Gadgets pick their material with sneak + left-click on a block,
 * so that sneak + right-click can build (see mixin.GadgetSneakBuildMixin). Out of the box sneak + right-click picks
 * the material, which made building while crouched at a ledge impossible (shanni_boi and Ben, 2026-11-18).
 * Server-side only: the client already sends left-clicks on blocks, and the server answers a cancelled one by sending
 * the block back, so players need no update. The block is never broken while picking.
 */
public final class GadgetPick {
    private GadgetPick() {}

    public static boolean isPicker(ItemStack stack) {
        return stack.getItem() instanceof GadgetBuilding || stack.getItem() instanceof GadgetExchanger;
    }

    /** Whether sneaking with this gadget still means its own sneak action: binding to an inventory, which uses sneak + right-click. */
    public static boolean keepsSneakAction(ItemStack stack) {
        return GadgetNBT.getSetting(stack, GadgetNBT.ToggleableSettings.BIND.getName());
    }

    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        Player player = event.getEntity();
        ItemStack gadget = player.getMainHandItem();
        if (!player.isShiftKeyDown() || !isPicker(gadget)) return;
        // Cancel every stage of the click (start, stop, abort), so the block is neither broken nor left half-mined.
        event.setCanceled(true);
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) return;
        // The same test and setter the gadget's own sneak + right-click used.
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (GadgetUtils.isValidBlockState(state, level, pos) && !(state.getBlock() instanceof RenderBlock)) {
            GadgetUtils.setBlockState(gadget, state);
        } else {
            player.displayClientMessage(Component.translatable("buildinggadgets2.messages.invalidblock"), true);
        }
    }
}
