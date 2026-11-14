package memtweaks.mixin.modernfix;

import java.util.Collections;
import java.util.Set;
import memtweaks.ModelLocationShare;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells {@link ModelLocationShare} each time ModernFix has finished building its set of every model location.
 */
@Pseudo
@Mixin(targets = "org.embeddedt.modernfix.neoforge.dynresources.ModelBakeEventHelper", remap = false)
public abstract class ModelBakeEventHelperMixin {
    @Shadow(remap = false) @Final private Set topLevelModelLocations;

    @SuppressWarnings("unchecked")
    @Inject(method = "<init>", at = @At("RETURN"), remap = false, require = 0)
    private void memtweaks$shareLocations(CallbackInfo ci) {
        ModelLocationShare.keep(Collections.unmodifiableSet(this.topLevelModelLocations));
    }
}
