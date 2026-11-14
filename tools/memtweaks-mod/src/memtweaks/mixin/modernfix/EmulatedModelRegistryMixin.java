package memtweaks.mixin.modernfix;

import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import memtweaks.LazyModelReplace;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands replaceAll on ModernFix's emulated model registry to {@link LazyModelReplace}. */
@Pseudo
@Mixin(targets = "org.embeddedt.modernfix.neoforge.dynresources.ModelBakeEventHelper$EmulatedModelRegistry", remap = false)
public abstract class EmulatedModelRegistryMixin {
    @Shadow(remap = false) @Final private Set ourModelLocations;

    @Shadow(remap = false) protected abstract Map delegate();

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "replaceAll", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void memtweaks$defer(BiFunction function, CallbackInfo ci) {
        if (LazyModelReplace.defer((Map) (Object) this, this.delegate(), this.ourModelLocations, function)) ci.cancel();
    }
}
