package memtweaks.mixin.modernfix;

import java.util.Set;
import memtweaks.ModelLocationShare;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets ModernFix's second set of model locations in a resource load be copied from its first. See {@link ModelLocationShare}. */
@Pseudo
@Mixin(targets = "org.embeddedt.modernfix.neoforge.dynresources.ModelLocationBuilder", remap = false)
public abstract class ModelLocationBuilderMixin {
    @SuppressWarnings("rawtypes")
    @Inject(method = "generateForBlock", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void memtweaks$copyInsteadOfRebuilding(Set destination, Block block, ResourceLocation id, CallbackInfo ci) {
        if (ModelLocationShare.fillFromPrevious(destination)) ci.cancel();
    }
}
