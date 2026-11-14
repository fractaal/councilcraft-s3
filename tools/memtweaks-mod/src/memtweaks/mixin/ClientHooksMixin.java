package memtweaks.mixin;

import java.util.Map;
import memtweaks.LazyModelReplace;
import memtweaks.ModelLocationShare;
import net.minecraft.client.resources.model.ModelBakery;
import net.neoforged.neoforge.client.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks the model events of a resource load for {@link LazyModelReplace} and {@link ModelLocationShare}. */
@Mixin(value = ClientHooks.class, remap = false)
public abstract class ClientHooksMixin {
    @Inject(method = "onModifyBakingResult", at = @At("HEAD"), remap = false, require = 0)
    private static void memtweaks$modelEventStarted(Map<?, ?> models, Map<?, ?> stitchResults, ModelBakery bakery, CallbackInfo ci) {
        ModelLocationShare.eventStarted();
        LazyModelReplace.beginEvent(((ModelBakeryAccessor) bakery).memtweaks$bakedTopLevelModels());
    }

    @Inject(method = "onModifyBakingResult", at = @At("RETURN"), remap = false, require = 0)
    private static void memtweaks$modelEventFinished(CallbackInfo ci) {
        LazyModelReplace.endEvent();
        ModelLocationShare.eventFinished();
    }

    @Inject(method = "onModelBake", at = @At("RETURN"), remap = false, require = 0)
    private static void memtweaks$bakingCompleted(CallbackInfo ci) {
        ModelLocationShare.bakingCompleted();
    }
}
