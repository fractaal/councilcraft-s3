package memtweaks.mixin;

import java.util.Map;
import memtweaks.LazyEntryMap;
import memtweaks.MemTweaks;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wraps the bakery's map of model files so that looping over it doesn't parse every file. See {@link LazyEntryMap}. */
@Mixin(ModelBakery.class)
public abstract class ModelBakeryLazyFilesMixin {
    @Shadow @Final @Mutable private Map<ResourceLocation, BlockModel> modelResources;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void memtweaks$lazyModelFileEntries(CallbackInfo ci) {
        if (MemTweaks.lazyModelFileEntries() && !(this.modelResources instanceof LazyEntryMap)) {
            this.modelResources = new LazyEntryMap<>(this.modelResources);
        }
    }
}
