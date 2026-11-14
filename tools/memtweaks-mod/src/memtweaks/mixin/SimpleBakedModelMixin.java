package memtweaks.mixin;

import java.util.List;
import java.util.Map;
import memtweaks.MemTweaks;
import memtweaks.QuadShare;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** When a baked model is built, swap its quads for the shared copies. See {@link QuadShare}. */
@Mixin(SimpleBakedModel.class)
public abstract class SimpleBakedModelMixin {
    @Shadow @Final protected List<BakedQuad> unculledFaces;
    @Shadow @Final protected Map<Direction, List<BakedQuad>> culledFaces;

    @Inject(method = "<init>*", at = @At("RETURN"), require = 0)
    private void memtweaks$shareQuads(CallbackInfo ci) {
        if (MemTweaks.shareIdenticalQuads()) QuadShare.share(this.unculledFaces, this.culledFaces);
    }
}
