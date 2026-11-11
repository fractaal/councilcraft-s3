package skyislands.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import skyislands.FastGen;
import skyislands.SkyBiomes;
import skyislands.SkyTerrain;

/**
 * Wraps the resolver a sky-island chunk fills its biomes from (skyislands.SkyBiomes). The priority puts this outside
 * TerraBlender's own wrapper of the same argument, so samples near land still go through TerraBlender and
 * Lithostitched exactly as before.
 */
@Mixin(value = NoiseBasedChunkGenerator.class, priority = 2000)
abstract class SkyBiomesMixin {
    @ModifyArg(method = "doCreateBiomes", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;fillBiomesFromNoise(Lnet/minecraft/world/level/biome/BiomeResolver;Lnet/minecraft/world/level/biome/Climate$Sampler;)V"), index = 0)
    private BiomeResolver skyislands$skyBiomes(BiomeResolver resolver, @Local(argsOnly = true) RandomState state) {
        if (!FastGen.enabled()) return resolver;
        SkyTerrain terrain = SkyTerrain.of(state);
        return terrain == null ? resolver : new SkyBiomes(resolver, terrain);
    }
}
