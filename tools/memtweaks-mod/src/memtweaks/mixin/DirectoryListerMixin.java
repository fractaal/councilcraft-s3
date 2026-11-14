package memtweaks.mixin;

import java.util.Map;
import memtweaks.MemTweaks;
import net.minecraft.client.renderer.texture.atlas.sources.DirectoryLister;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A "directory" atlas source lists that texture folder in every mod, not only the mod that declared it.
 * The Grappling Hook mod declares the whole "entity" folder for the block atlas to get its own hook and
 * rope textures, which pulls every mod's entity textures in: 42 of the atlas's 99 megapixels in this pack
 * (heap dump, 2026-11-14), enough to double the atlas to 16384x8192. This limits that one source to the
 * Grappling Hook mod's own textures. Sources for specific subfolders such as "entity/chest" are untouched.
 */
@Mixin(DirectoryLister.class)
public abstract class DirectoryListerMixin {
    @Shadow @Final private String sourcePath;

    @Redirect(method = "run", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/resources/FileToIdConverter;listMatchingResources(Lnet/minecraft/server/packs/resources/ResourceManager;)Ljava/util/Map;"))
    private Map<ResourceLocation, Resource> memtweaks$ownEntityTexturesOnly(FileToIdConverter converter, ResourceManager resources) {
        if ("entity".equals(this.sourcePath) && MemTweaks.limitEntityAtlasSource()) {
            Map<ResourceLocation, Resource> own = converter.listMatchingResourcesFromNamespace(resources, "grapplemod");
            MemTweaks.logEntityAtlasSource(own.size());
            return own;
        }
        return converter.listMatchingResources(resources);
    }
}
