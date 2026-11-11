package skyislands.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The corner values a NoiseInterpolator keeps for the current and next x slice of cells: [cellZ][cellY]. */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$NoiseInterpolator")
public interface NoiseInterpolatorAccess {
    @Accessor("slice0")
    double[][] skyislands$slice0();

    @Accessor("slice1")
    double[][] skyislands$slice1();
}
