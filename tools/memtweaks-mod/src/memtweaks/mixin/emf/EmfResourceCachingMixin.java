package memtweaks.mixin.emf;

import java.util.Map;
import memtweaks.MemTweaks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entity Model Features lists everything under emf/cem and optifine/cem once per resource load and remembers
 * what exists. For a file it has not seen it still asks the resource manager, which checks each of several
 * hundred mod packs in turn. It asks about a few thousand files that don't exist (one per entity model part
 * and fallback folder), about two seconds on every load. A file under those two folders that the listing did
 * not find is not there, so this answers "no" for it without asking.
 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.utils.EMFResourceCaching", remap = false)
public abstract class EmfResourceCachingMixin {
    @Shadow(remap = false) @Final public static Map<String, Boolean> RESOURCE_EXISTENCE_CACHE;
    @Shadow(remap = false) private static boolean isPopulated;

    @Inject(method = "resourceExists", cancellable = true, remap = false, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/ResourceManager;getResource(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;", remap = true))
    private static void memtweaks$notListedMeansMissing(ResourceManager manager, ResourceLocation file, CallbackInfoReturnable<Boolean> cir) {
        if (!isPopulated || !MemTweaks.trustEmfFolderListing()) return;
        String path = file.getPath();
        if (path.startsWith("emf/cem/") || path.startsWith("optifine/cem/")) {
            RESOURCE_EXISTENCE_CACHE.put(file.toString(), Boolean.FALSE);
            cir.setReturnValue(false);
        }
    }
}
