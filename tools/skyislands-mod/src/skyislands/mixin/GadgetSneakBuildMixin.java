package skyislands.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import skyislands.GadgetPick;

/**
 * Building Gadgets 2: right-click with the Building or Exchanging Gadget builds whether or not the player is sneaking,
 * so they can build while crouched at a ledge. Picking the material moves to sneak + left-click (skyislands.GadgetPick).
 * With the gadget's "bind to inventory" mode on, sneak + right-click still binds, as before.
 * Optional: without Building Gadgets 2 this mixin doesn't apply.
 */
@Pseudo
@Mixin(targets = "com.direwolf20.buildinggadgets2.common.items.BaseGadget")
abstract class GadgetSneakBuildMixin {
    @WrapOperation(method = "use", remap = false,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;isShiftKeyDown()Z", remap = false))
    private boolean skyislands$buildWhileSneaking(Player player, Operation<Boolean> isShiftKeyDown,
                                                  @Local(argsOnly = true) InteractionHand hand) {
        var gadget = player.getItemInHand(hand);
        if (GadgetPick.isPicker(gadget) && !GadgetPick.keepsSneakAction(gadget)) return false;
        return isShiftKeyDown.call(player);
    }
}
