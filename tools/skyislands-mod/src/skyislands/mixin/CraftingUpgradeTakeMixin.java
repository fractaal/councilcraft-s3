package skyislands.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.upgrades.crafting.CraftingUpgradeContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import skyislands.CraftingResultHold;

/**
 * The Crafting Upgrade's result slot (the second anonymous class in CraftingUpgradeContainer): holds its container's
 * result updates back while it takes a craft, and lets the one owed run when the take is done, however it ends. See
 * skyislands.mixin.CraftingUpgradeHoldMixin for why.
 * Optional: without Sophisticated Core this mixin doesn't apply.
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.crafting.CraftingUpgradeContainer$2")
abstract class CraftingUpgradeTakeMixin {
    @Shadow(remap = false)
    @Final
    CraftingUpgradeContainer this$0;

    @WrapMethod(method = "onTake", remap = false)
    private void skyislands$oneResultPerTake(Player player, ItemStack stack, Operation<Void> onTake) {
        CraftingResultHold container = (CraftingResultHold) (Object) this$0;
        container.skyislands$holdResult();
        try {
            onTake.call(player, stack);
        } finally {
            container.skyislands$releaseResult();
        }
    }
}
