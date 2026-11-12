package skyislands;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;

/**
 * Structures that hang in the open sky of a sky-island world. Many build supports under themselves until they meet
 * ground (leg and pillar processors, block-column features, vanilla's fillColumnDown); with no ground below, those run
 * as bare columns all the way to the bottom of the world. After a structure's pieces are placed in a chunk, this cuts
 * every such column that has no land to stand on down to a short stub. A structure meant to be underground gets a mass
 * of rock under its pieces instead, deepest under their middle and uneven underneath like an island's underside, so it
 * sits in a floating rock of its own.
 *
 * It is decided column by column from the pieces' boxes and the terrain's shape, never from what neighbouring chunks
 * hold, so chunks agree with each other whatever order they generate in. Structures with a terrain adaptation are
 * left alone: the game already builds ground under those. Turn it off with -Dskyislands.structureFootings=false.
 */
public final class StructureFootings {
    private static final boolean ON = !"false".equals(System.getProperty("skyislands.structureFootings"));
    /** How far the rock reaches out past a piece's walls. */
    private static final int MARGIN = 2;
    /** How much of a cut support is left hanging under a structure that gets no rock. */
    private static final int KEEP = 3;
    /** Land this close under a piece counts as ground it stands on: its columns are left as they are. */
    private static final int NEAR = 20;
    private static final Set<GenerationStep.Decoration> UNDERGROUND = EnumSet.of(GenerationStep.Decoration.UNDERGROUND_STRUCTURES,
            GenerationStep.Decoration.UNDERGROUND_DECORATION, GenerationStep.Decoration.STRONGHOLDS);
    private static final BlockState AIR = Blocks.AIR.defaultBlockState(), STONE = Blocks.STONE.defaultBlockState(),
            DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();

    private StructureFootings() {}

    /** Called when `start` has placed its pieces in the chunk whose columns are `box`. */
    public static void apply(StructureStart start, WorldGenLevel level, ChunkGenerator generator, BoundingBox box) {
        if (!ON || !FastGen.isSky(generator)) return;
        Structure structure = start.getStructure();
        if (structure.terrainAdaptation() != TerrainAdjustment.NONE) return;
        boolean rock = UNDERGROUND.contains(structure.step());
        List<BoundingBox> near = new ArrayList<>();
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox b = piece.getBoundingBox();
            if (b.maxX() + MARGIN < box.minX() || b.minX() - MARGIN > box.maxX() || b.maxZ() + MARGIN < box.minZ() || b.minZ() - MARGIN > box.maxZ()) continue;
            near.add(b);
            x0 = Math.min(x0, b.minX() - MARGIN); x1 = Math.max(x1, b.maxX() + MARGIN);
            z0 = Math.min(z0, b.minZ() - MARGIN); z1 = Math.max(z1, b.maxZ() + MARGIN);
        }
        if (near.isEmpty()) return;
        SkyTerrain terrain = SkyTerrain.of(level.getLevel().getChunkSource().randomState());
        if (terrain == null) return;
        int floor = level.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = Math.max(x0, box.minX()); x <= Math.min(x1, box.maxX()); x++) {
            for (int z = Math.max(z0, box.minZ()); z <= Math.min(z1, box.maxZ()); z++) {
                // The lowest piece over this column, and for rock the lowest piece whose surroundings reach it.
                int under = Integer.MAX_VALUE, rockUnder = Integer.MAX_VALUE;
                double inset = 0.0;
                for (BoundingBox b : near) {
                    if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ()) under = Math.min(under, b.minY());
                    if (!rock || b.getXSpan() < 3 || b.getZSpan() < 3) continue;
                    int ex = Math.min(x - (b.minX() - MARGIN), b.maxX() + MARGIN - x), ez = Math.min(z - (b.minZ() - MARGIN), b.maxZ() + MARGIN - z);
                    if (ex < 0 || ez < 0) continue;
                    // Rounded corners.
                    if (ex < MARGIN && ez < MARGIN && Math.hypot(MARGIN - ex, MARGIN - ez) > MARGIN) continue;
                    rockUnder = Math.min(rockUnder, b.minY());
                    inset = Math.max(inset, Math.min(ex, ez) + 1.0);
                }
                boolean rockHere = rockUnder != Integer.MAX_VALUE;
                int top = Math.min(under, rockUnder) - 1;
                if (top == Integer.MAX_VALUE - 1 || top <= floor) continue;
                if (!rockHere && level.getBlockState(pos.set(x, top, z)).isAir()) continue;
                // Land at or just under the piece: it stands on that. Land further down: a cut stops above it.
                double[] spans = terrain.spansAt(x, z);
                boolean stands = false;
                double landTop = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < spans.length; i += 3) {
                    if (spans[i] >= top - NEAR && spans[i + 1] <= top + 3) stands = true;
                    else if (spans[i] < top - NEAR) landTop = Math.max(landTop, spans[i]);
                }
                if (stands) continue;
                int stop = landTop == Double.NEGATIVE_INFINITY ? floor - 1 : Math.max(floor - 1, (int) Math.ceil(landTop) + 2);
                int y = top;
                if (rockHere) {
                    double depth = Math.min(18.0, 1.5 + 1.4 * inset) * (0.8 + 0.35 * terrain.shapeNoise(x, z, 9.0))
                            + 4.0 * Math.max(0.0, terrain.shapeNoise(x + 977.0, z - 431.0, 4.0) - 0.3);
                    int bottom = Math.max(stop + 1, top - (int) Math.max(1.0, Math.round(depth)) + 1);
                    for (; y >= bottom; y--) level.setBlock(pos.set(x, y, z), rockAt(x, y, z), 2);
                } else {
                    int end = top;
                    while (end > stop && !level.getBlockState(pos.set(x, end, z)).isAir()) end--;
                    if (top - end <= KEEP + 5) continue;
                    y = top - KEEP;
                }
                for (; y > stop; y--) {
                    if (level.getBlockState(pos.set(x, y, z)).isAir()) break;
                    level.setBlock(pos, AIR, 2);
                }
            }
        }
    }

    /** Stone, turning to deepslate between y 8 and 0 as the world's own stone does. */
    private static BlockState rockAt(int x, int y, int z) {
        if (y >= 8) return STONE;
        if (y < 0) return DEEPSLATE;
        return ((Mth.getSeed(x, y, z) >>> 16) & 0xFF) / 256.0 < (8 - y) / 8.0 ? DEEPSLATE : STONE;
    }
}
