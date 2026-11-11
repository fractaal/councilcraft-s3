package skyislands.mixin;

import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import skyislands.FastFill;
import skyislands.FastGen;
import skyislands.SkyTerrain;

/**
 * Decides whether a cell of the noise fill is all air. Each NoiseInterpolator holds the cell-corner values of one
 * interpolated density; inside a cell every block's value is a trilinear blend of its 8 corners, so if all 8 corners of
 * every interpolator are below zero, every block in the cell is too. With no structure blending added on top (the chunk
 * was set up with a zero Beardifier) and no lake water in the cell, each of those blocks is air.
 */
@Mixin(NoiseChunk.class)
abstract class NoiseChunkMixin implements FastFill {
    @Shadow @Final List<?> interpolators;
    @Shadow @Final int cellNoiseMinY;
    @Shadow @Final private int firstCellZ;
    @Shadow @Final int cellWidth;
    @Shadow @Final int cellHeight;
    @Shadow private int cellStartBlockX;

    @Shadow public abstract void selectCellYZ(int cellY, int cellZ);
    @Shadow protected abstract BlockState getInterpolatedState();

    @Unique private boolean skyislands$fast;
    @Unique private SkyTerrain.ArchPicker skyislands$picker;
    @Unique private boolean skyislands$air;

    @Override
    public void skyislands$setup(boolean fast, SkyTerrain.ArchPicker picker) {
        skyislands$fast = fast;
        skyislands$picker = picker;
    }

    @Override
    public void skyislands$selectCell(int cellY, int cellZ) {
        if (skyislands$fast && FastGen.enabled() && skyislands$allAir(cellY, cellZ)) {
            skyislands$air = true;
            FastGen.CELLS_SKIPPED.incrementAndGet();
            return;
        }
        skyislands$air = false;
        if (skyislands$fast) FastGen.CELLS_FILLED.incrementAndGet();
        selectCellYZ(cellY, cellZ);
    }

    @Override
    public boolean skyislands$air() {
        return skyislands$air;
    }

    @Override
    public BlockState skyislands$state() {
        return getInterpolatedState();
    }

    @Unique
    private boolean skyislands$allAir(int cellY, int cellZ) {
        for (Object o : interpolators) {
            NoiseInterpolatorAccess ia = (NoiseInterpolatorAccess) o;
            double[] a0 = ia.skyislands$slice0()[cellZ], a1 = ia.skyislands$slice0()[cellZ + 1];
            double[] b0 = ia.skyislands$slice1()[cellZ], b1 = ia.skyislands$slice1()[cellZ + 1];
            if (a0[cellY] >= 0 || a0[cellY + 1] >= 0 || a1[cellY] >= 0 || a1[cellY + 1] >= 0
                    || b0[cellY] >= 0 || b0[cellY + 1] >= 0 || b1[cellY] >= 0 || b1[cellY + 1] >= 0) return false;
        }
        if (skyislands$picker == null) return false;
        int y0 = (cellNoiseMinY + cellY) * cellHeight, z0 = (firstCellZ + cellZ) * cellWidth;
        return !skyislands$picker.mayHaveWater(cellStartBlockX, z0, cellWidth, y0, y0 + cellHeight - 1);
    }
}
