package skyislands.mixin;

import java.util.Arrays;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import skyislands.FastFill;
import skyislands.FastGen;

/**
 * The noise fill asks the Beardifier for a whole cell's densities at once (NoiseChunk.selectCellYZ fills each cached
 * function's array, and the final density adds the Beardifier's array to the terrain's). Vanilla computes every block of
 * the cell; this fills the cell with zeros when it lies outside every box the chunk's blending can reach (BlendReach),
 * where Beardifier.compute, with every mod's addition to it, returns exactly 0.0. Anywhere else, and for any caller that
 * isn't a noise chunk filling a cell, it does what vanilla's default does.
 */
@Mixin(Beardifier.class)
abstract class BeardifierMixin {
    public void fillArray(double[] array, DensityFunction.ContextProvider ctx) {
        if (ctx instanceof FastFill f && f.skyislands$beardZeroInCell(this)) {
            Arrays.fill(array, 0.0);
            FastGen.BEARD_CELLS_ZEROED.incrementAndGet();
            return;
        }
        ctx.fillAllDirectly(array, (DensityFunction) (Object) this);
    }
}
