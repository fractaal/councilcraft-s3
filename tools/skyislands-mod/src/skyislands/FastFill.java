package skyislands;

import net.minecraft.world.level.block.state.BlockState;

/** Added to NoiseChunk by skyislands.mixin.NoiseChunkMixin: the state for skipping all-air cells in the noise fill. */
public interface FastFill {
    /**
     * fast: this chunk may skip all-air cells. picker: its lake water, which a skipped cell must not contain. reach: the
     * boxes its structure blending can reach (BlendReach), which a skipped cell must not touch; null if unknown.
     */
    void skyislands$setup(boolean fast, SkyTerrain.ArchPicker picker, int[] reach);

    /** True while this noise chunk fills a cell that its Beardifier beard can't reach, so beard's density there is 0. */
    boolean skyislands$beardZeroInCell(Object beard);

    /** NoiseChunk.selectCellYZ, unless the cell is all air; then the cell is marked and nothing is computed for it. */
    void skyislands$selectCell(int cellY, int cellZ);

    /** True while the selected cell is a skipped all-air cell. */
    boolean skyislands$air();

    /** NoiseChunk.getInterpolatedState, which is protected. */
    BlockState skyislands$state();
}
