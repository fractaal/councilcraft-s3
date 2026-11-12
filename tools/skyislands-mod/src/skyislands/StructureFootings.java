package skyislands;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.neoforged.neoforge.common.Tags;

/**
 * Structures over the open sky of a sky-island world, where there is no ground for them.
 *
 * A structure that looks for the ground finds none and lands on the bottom of the world. While a structure is being
 * planned (searching), a column with no land answers with a made-up ground height instead (SkyTerrain.padHeight, a
 * smooth field at about the height islands have in that part of the sky), so the structure is planned at a height where
 * it belongs. When its pieces are then placed, the made-up ground is made real under them: a small island of its own,
 * with the biome's soil on top and rock underneath, wherever the terrain has no land of its own there.
 *
 * A structure meant to be underground has a height of its own, and gets a mass of rock under its pieces instead.
 *
 * Many structures also build supports under themselves until they meet ground (leg and pillar processors, block-column
 * features, vanilla's fillColumnDown); with no ground below, those run as bare columns to the bottom of the world.
 * After a structure's pieces are placed, every such column that has no land to stand on is cut to a short stub.
 *
 * All of it is decided column by column from the pieces' boxes and the terrain's shape, never from what neighbouring
 * chunks hold, so chunks agree whatever order they generate in. -Dskyislands.structureIslands=false turns the made-up
 * ground and the islands off, -Dskyislands.structureFootings=false all of it.
 */
public final class StructureFootings {
    private static final boolean ON = !"false".equals(System.getProperty("skyislands.structureFootings"));
    private static final boolean ISLANDS = ON && !"false".equals(System.getProperty("skyislands.structureIslands"));
    /** How far rock reaches out past an underground piece's walls, and land past a surface piece's. */
    private static final int ROCK_MARGIN = 2, LAND_MARGIN = 7;
    /** How much of a cut support is left hanging under a structure that gets no land or rock. */
    private static final int KEEP = 3;
    /** Land this close under a piece counts as ground it stands on: its columns are left as they are. */
    private static final int NEAR = 20;
    private static final Set<GenerationStep.Decoration> UNDERGROUND = EnumSet.of(GenerationStep.Decoration.UNDERGROUND_STRUCTURES,
            GenerationStep.Decoration.UNDERGROUND_DECORATION, GenerationStep.Decoration.STRONGHOLDS);
    private static final BlockState AIR = Blocks.AIR.defaultBlockState(), STONE = Blocks.STONE.defaultBlockState(),
            DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();

    private StructureFootings() {}

    /**
     * The box a chunk must touch to be given `start` to place. The game uses the pieces' own box; the land around a
     * structure reaches further, and a chunk that holds only that rim has to build its part of it.
     */
    public static BoundingBox referenceBox(StructureStart start) {
        return ISLANDS ? start.getBoundingBox().inflatedBy(LAND_MARGIN + 3) : start.getBoundingBox();
    }

    // ---- The made-up ground while a structure is planned -----------------------------------------------------------

    private static final ThreadLocal<int[]> SEARCHING = ThreadLocal.withInitial(() -> new int[1]);

    /** A structure starts being planned on this thread (its place checked, or its pieces laid out). */
    public static void enterSearch() { SEARCHING.get()[0]++; }

    public static void exitSearch() {
        int[] d = SEARCHING.get();
        if (d[0] > 0) d[0]--;
    }

    /**
     * The ground height (first free block) a planned structure is given at a column with no land, or
     * Integer.MIN_VALUE when it should get the generator's own answer.
     */
    public static int searchHeight(net.minecraft.world.level.levelgen.RandomState state, int x, int z) {
        if (!ISLANDS || SEARCHING.get()[0] == 0) return Integer.MIN_VALUE;
        SkyTerrain terrain = SkyTerrain.of(state);
        if (terrain == null) return Integer.MIN_VALUE;
        double pad = terrain.padHeight(x, z);
        return Double.isNaN(pad) ? Integer.MIN_VALUE : (int) pad;
    }

    /**
     * Whether a surface structure was planned on the made-up ground: that height is within its pieces or just under
     * them. One meant to float well above the ground, or planned at a height of its own, is not.
     */
    private static boolean onMadeUpGround(StructureStart start, SkyTerrain terrain) {
        if (!ISLANDS || start.getPieces().isEmpty()) return false;
        BoundingBox first = start.getPieces().get(0).getBoundingBox(), all = start.getBoundingBox();
        double pad = terrain.padHeight((first.minX() + first.maxX()) / 2, (first.minZ() + first.maxZ()) / 2);
        return !Double.isNaN(pad) && all.minY() <= pad + 6.0 && all.maxY() >= pad - 16.0;
    }

    /**
     * The y range in which the chunk at the centre of `level` may get land for its structures, or null. Decoration
     * only looks for biomes in sections near land (FastGen.decorationSections), and this land isn't there yet when it
     * looks, so without this nothing would grow on it.
     */
    static int[] landRange(WorldGenLevel level, ChunkAccess chunk) {
        if (!ISLANDS || !(level instanceof WorldGenRegion region) || !chunk.getPos().equals(region.getCenter())) return null;
        if (chunk.getAllReferences().isEmpty()) return null;
        SkyTerrain terrain = SkyTerrain.of(level.getLevel().getChunkSource().randomState());
        if (terrain == null) return null;
        int[] range = null;
        for (StructureStart start : level.getLevel().structureManager().forWorldGenRegion(region)
                .startsForStructure(chunk.getPos(), s -> !UNDERGROUND.contains(s.step()))) {
            if (!onMadeUpGround(start, terrain)) continue;
            int pad = (int) terrain.padHeight(chunk.getPos().getMiddleBlockX(), chunk.getPos().getMiddleBlockZ());
            if (range == null) range = new int[]{pad - 28, pad + 20};
        }
        return range;
    }

    // ---- Land, rock and cut supports when a structure is placed ----------------------------------------------------

    /** Called before `start` places its pieces in the chunk whose columns are `box`: the land or rock under them. */
    public static void before(StructureStart start, WorldGenLevel level, ChunkGenerator generator, BoundingBox box) {
        apply(start, level, generator, box, true);
    }

    /** Called after `start` has placed its pieces in the chunk: cuts the supports that have nothing to stand on. */
    public static void after(StructureStart start, WorldGenLevel level, ChunkGenerator generator, BoundingBox box) {
        apply(start, level, generator, box, false);
    }

    private static void apply(StructureStart start, WorldGenLevel level, ChunkGenerator generator, BoundingBox box, boolean before) {
        if (!ON || !FastGen.isSky(generator)) return;
        Structure structure = start.getStructure();
        // A structure with a terrain adaptation gets land or rock like any other: in the open sky the game's own
        // adaptation has nothing to grow ground from. But nothing is cut under it, since next to an island the ground
        // under its pieces can be the adaptation's work, which the terrain's shape doesn't know about.
        if (!before && structure.terrainAdaptation() != TerrainAdjustment.NONE) return;
        boolean rock = UNDERGROUND.contains(structure.step());
        SkyTerrain terrain = SkyTerrain.of(level.getLevel().getChunkSource().randomState());
        if (terrain == null) return;
        boolean land = !rock && onMadeUpGround(start, terrain);
        if (before && !rock && !land) return;
        int margin = land ? LAND_MARGIN + 3 : ROCK_MARGIN;
        List<BoundingBox> near = new ArrayList<>();
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox b = piece.getBoundingBox();
            if (b.maxX() + margin < box.minX() || b.minX() - margin > box.maxX() || b.maxZ() + margin < box.minZ() || b.minZ() - margin > box.maxZ()) continue;
            near.add(b);
            x0 = Math.min(x0, b.minX() - margin); x1 = Math.max(x1, b.maxX() + margin);
            z0 = Math.min(z0, b.minZ() - margin); z1 = Math.max(z1, b.maxZ() + margin);
        }
        if (near.isEmpty()) return;
        int floor = level.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = Math.max(x0, box.minX()); x <= Math.min(x1, box.maxX()); x++) {
            for (int z = Math.max(z0, box.minZ()); z <= Math.min(z1, box.maxZ()); z++) {
                // The lowest piece over this column, and how far inside the land or rock around the pieces it is.
                int under = Integer.MAX_VALUE, rockUnder = Integer.MAX_VALUE;
                double inset = Double.NEGATIVE_INFINITY;
                double reach = land ? LAND_MARGIN + 3.0 * terrain.shapeNoise(x, z, 13.0) : ROCK_MARGIN;
                for (BoundingBox b : near) {
                    if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ()) under = Math.min(under, b.minY());
                    if (rock && (b.getXSpan() < 3 || b.getZSpan() < 3)) continue;
                    double ex = Math.min(x - (b.minX() - reach), b.maxX() + reach - x), ez = Math.min(z - (b.minZ() - reach), b.maxZ() + reach - z);
                    if (ex < 0 || ez < 0) continue;
                    // Rounded corners.
                    if (ex < reach && ez < reach && Math.hypot(reach - ex, reach - ez) > reach) continue;
                    rockUnder = Math.min(rockUnder, b.minY());
                    inset = Math.max(inset, Math.min(ex, ez) + 1.0);
                }
                boolean built = (rock || land) && rockUnder != Integer.MAX_VALUE;
                // Land is the made-up ground made real; rock hangs under the lowest piece.
                int top = Math.min(under, rockUnder) - 1;
                if (built && land) {
                    // A piece standing a little above the made-up ground (it took its height from an island beside
                    // it) gets the land brought up to it.
                    int ground = (int) terrain.padHeight(x, z) - 1;
                    top = under != Integer.MAX_VALUE && under - 1 > ground && under - 1 <= ground + 16 ? under - 1 : ground;
                }
                if (top == Integer.MAX_VALUE - 1 || top <= floor) continue;
                if (!built && (before || level.getBlockState(pos.set(x, top, z)).isAir())) continue;
                // Land at or just under here: the structure stands on that. Land further down: a cut stops above it.
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
                if (built) {
                    double depth = Math.min(land ? 24.0 : 18.0, 1.5 + (land ? 1.1 : 1.4) * inset) * (0.8 + 0.35 * terrain.shapeNoise(x, z, 9.0))
                            + 4.0 * Math.max(0.0, terrain.shapeNoise(x + 977.0, z - 431.0, 4.0) - 0.3);
                    int bottom = top - (int) Math.max(1.0, Math.round(depth)) + 1;
                    // A piece set down into the ground (a cellar, a sunken ruin) is wrapped in it, with rock under it
                    // that is deepest under its middle.
                    if (land && under != Integer.MAX_VALUE) bottom = Math.min(bottom, under - 1 - (int) Math.round(0.5 * depth));
                    bottom = Math.max(stop + 1, bottom);
                    if (before) {
                        Holder<Biome> biome = land ? level.getBiome(pos.set(x, top + 1, z)) : null;
                        for (; y >= bottom; y--) level.setBlock(pos.set(x, y, z), land ? soilAt(biome, x, y, z, top) : rockAt(x, y, z), 2);
                        continue;
                    }
                    y = bottom - 1;
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

    /** The biome's soil in the top four blocks (sand in deserts and on beaches, red sand in badlands, else grass and dirt), rock below. */
    private static BlockState soilAt(Holder<Biome> biome, int x, int y, int z, int top) {
        if (y < top - 3) return rockAt(x, y, z);
        if (biome.is(BiomeTags.IS_BADLANDS)) return (y == top ? Blocks.RED_SAND : Blocks.TERRACOTTA).defaultBlockState();
        if (biome.is(Tags.Biomes.IS_DESERT) || biome.is(BiomeTags.IS_BEACH)) return (y == top ? Blocks.SAND : Blocks.SANDSTONE).defaultBlockState();
        return (y == top ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState();
    }
}
