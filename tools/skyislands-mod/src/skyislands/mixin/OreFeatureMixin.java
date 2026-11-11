package skyislands.mixin;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import skyislands.FastGen;

/**
 * An ore blob whose whole neighbourhood is air can't replace anything. It isn't worked out block by block; it only
 * draws the same random numbers, so every later ore lands where it would have.
 */
@Mixin(OreFeature.class)
abstract class OreFeatureMixin {
    @Inject(method = "doPlace", at = @At("HEAD"), cancellable = true)
    private void skyislands$emptySky(WorldGenLevel level, RandomSource random, OreConfiguration cfg, double minX, double maxX,
                                     double minZ, double maxZ, double minY, double maxY, int x, int y, int z, int width,
                                     int height, CallbackInfoReturnable<Boolean> cir) {
        if (FastGen.oreBlobInEmptySky(level, cfg, minX, maxX, minZ, maxZ, minY, maxY)) {
            FastGen.oreRandomDraws(random, cfg);
            cir.setReturnValue(false);
        }
    }
}
