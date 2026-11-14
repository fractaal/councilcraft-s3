package memtweaks.mixin.puzzleslib;

import java.util.Set;
import memtweaks.ModelLocationShare;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reuses Puzzles Lib's set of all model locations within one model event. See {@link ModelLocationShare}. */
@Pseudo
@Mixin(targets = "fuzs.puzzleslib.neoforge.impl.client.event.NeoForgeClientEventInvokers", remap = false)
public abstract class PuzzlesModelLocationsMixin {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "getTopLevelModelLocations", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void memtweaks$reuse(CallbackInfoReturnable cir) {
        Set<?> kept = ModelLocationShare.get();
        if (kept != null) cir.setReturnValue(kept);
    }

    @SuppressWarnings("rawtypes")
    @Inject(method = "getTopLevelModelLocations", at = @At("RETURN"), remap = false, require = 0)
    private static void memtweaks$keep(CallbackInfoReturnable cir) {
        Object built = cir.getReturnValue();
        if (built instanceof Set<?> set) ModelLocationShare.keepOwn(set);
    }
}
