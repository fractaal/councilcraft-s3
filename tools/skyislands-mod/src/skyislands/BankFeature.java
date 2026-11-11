package skyislands;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.neoforged.neoforge.common.Tags;

/**
 * Dresses the edges of water: the bed under it, a waterline strip just above it, a bank ring and an outer ring, in
 * materials chosen by biome (mud in swamps, sand in deserts, gravel in cold places), as patches rather than speckle.
 * The PLANTS mode then adds grass and ferns along the banks, sugar cane at the water's edge, seagrass on the bed and
 * lily pads on calm water. It works from the blocks already in the chunk, so it dresses any water, lakes and rivers
 * alike, and does nothing in chunks with no water near them.
 */
public final class BankFeature extends Feature<NoneFeatureConfiguration> {
    /** How far from the water (blocks) the rings reach, and the search margin around the chunk. */
    private static final int REACH = 8;
    private static final int W = 16 + 2 * REACH;

    private final boolean plants;

    public BankFeature(boolean plants) {
        super(NoneFeatureConfiguration.CODEC);
        this.plants = plants;
    }

    /** Materials per ring. A ring's blocks are picked by a patch noise; share is how much of the ring is dressed at all. */
    private record Style(Block[] bed, Block[] waterline, Block[] bank, double bankShare, Block[] outer, double outerShare,
                         double ferns) {}

    private static Block[] b(Block... blocks) { return blocks; }

    private static final Style TEMPERATE = new Style(b(Blocks.SAND, Blocks.GRAVEL, Blocks.CLAY, Blocks.SAND),
            b(Blocks.SAND, Blocks.GRAVEL, Blocks.SAND), b(Blocks.COARSE_DIRT, Blocks.GRAVEL, Blocks.COARSE_DIRT), 0.45,
            b(Blocks.COARSE_DIRT), 0.12, 0.25);
    private static final Style SWAMP = new Style(b(Blocks.MUD, Blocks.CLAY, Blocks.MUD), b(Blocks.MUD),
            b(Blocks.MUD, Blocks.MUDDY_MANGROVE_ROOTS, Blocks.MUD), 0.6, b(Blocks.MUD, Blocks.MOSS_BLOCK), 0.25, 0.4);
    private static final Style JUNGLE = new Style(b(Blocks.CLAY, Blocks.MUD, Blocks.SAND), b(Blocks.MUD, Blocks.COARSE_DIRT),
            b(Blocks.PODZOL, Blocks.MOSS_BLOCK, Blocks.COARSE_DIRT), 0.5, b(Blocks.MOSS_BLOCK), 0.2, 0.6);
    private static final Style SANDY = new Style(b(Blocks.SAND, Blocks.SANDSTONE, Blocks.SAND), b(Blocks.SAND),
            b(Blocks.SAND), 0.8, b(Blocks.SAND), 0.35, 0.0);
    private static final Style BADLANDS = new Style(b(Blocks.RED_SAND, Blocks.GRAVEL), b(Blocks.RED_SAND),
            b(Blocks.RED_SAND, Blocks.COARSE_DIRT), 0.7, b(Blocks.RED_SAND), 0.3, 0.0);
    private static final Style DRY = new Style(b(Blocks.SAND, Blocks.GRAVEL), b(Blocks.SAND, Blocks.COARSE_DIRT),
            b(Blocks.COARSE_DIRT), 0.55, b(Blocks.COARSE_DIRT), 0.2, 0.05);
    private static final Style COLD = new Style(b(Blocks.GRAVEL, Blocks.CLAY, Blocks.GRAVEL), b(Blocks.GRAVEL),
            b(Blocks.GRAVEL, Blocks.STONE, Blocks.COARSE_DIRT), 0.5, b(Blocks.GRAVEL), 0.15, 0.3);
    private static final Style TAIGA = new Style(b(Blocks.GRAVEL, Blocks.CLAY, Blocks.SAND), b(Blocks.GRAVEL, Blocks.COARSE_DIRT),
            b(Blocks.PODZOL, Blocks.COARSE_DIRT, Blocks.MOSS_BLOCK), 0.5, b(Blocks.PODZOL), 0.2, 0.6);
    private static final Style STONY = new Style(b(Blocks.GRAVEL, Blocks.STONE), b(Blocks.GRAVEL, Blocks.STONE),
            b(Blocks.STONE, Blocks.GRAVEL, Blocks.ANDESITE), 0.55, b(Blocks.GRAVEL), 0.15, 0.2);

    /** Null: leave this biome's water edges as they are. */
    private static Style style(Holder<Biome> b) {
        if (b.is(Tags.Biomes.IS_MUSHROOM)) return null;
        if (b.is(Tags.Biomes.IS_BADLANDS)) return BADLANDS;
        if (b.is(Tags.Biomes.IS_DESERT) || b.is(Tags.Biomes.IS_SANDY)) return SANDY;
        if (b.is(Tags.Biomes.IS_SWAMP)) return SWAMP;
        if (b.is(Tags.Biomes.IS_JUNGLE) || b.is(Tags.Biomes.IS_LUSH)) return JUNGLE;
        if (b.is(Tags.Biomes.IS_SNOWY) || b.is(Tags.Biomes.IS_COLD_OVERWORLD)) return COLD;
        if (b.is(Tags.Biomes.IS_SAVANNA) || b.is(Tags.Biomes.IS_DRY_OVERWORLD)) return DRY;
        if (b.is(Tags.Biomes.IS_TAIGA) || b.is(Tags.Biomes.IS_CONIFEROUS_TREE)) return TAIGA;
        if (b.is(Tags.Biomes.IS_MOUNTAIN) || b.is(Tags.Biomes.IS_STONY_SHORES)) return STONY;
        return TEMPERATE;
    }

    private static volatile long noiseSeed = Long.MIN_VALUE;
    private static volatile SimplexNoise noise;

    private static SimplexNoise noise(long seed) {
        if (noise == null || noiseSeed != seed) {
            noise = new SimplexNoise(new LegacyRandomSource(seed ^ 0x2B992DDFA23249D6L));
            noiseSeed = seed;
        }
        return noise;
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        int x0 = ctx.origin().getX() & ~15, z0 = ctx.origin().getZ() & ~15, wx0 = x0 - REACH, wz0 = z0 - REACH;
        int minY = level.getMinBuildHeight();
        // The top block of every column in the chunk and its margin, and whether it is water.
        int[] top = new int[W * W], floor = new int[W * W];
        boolean[] water = new boolean[W * W];
        boolean any = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < W; i++) {
            for (int j = 0; j < W; j++) {
                int x = wx0 + i, z = wz0 + j, k = i * W + j;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
                // Empty columns (the void between islands) get a value below anything, so they never count as land.
                top[k] = y <= minY ? Integer.MIN_VALUE : y;
                if (y <= minY) continue;
                if (level.getFluidState(pos.set(x, y, z)).is(FluidTags.WATER) && level.getBlockState(pos).is(Blocks.WATER)) {
                    water[k] = true;
                    floor[k] = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
                    any = true;
                }
            }
        }
        if (!any) return false;
        SimplexNoise n = noise(level.getSeed());
        RandomSource random = ctx.random();
        for (int i = REACH; i < REACH + 16; i++) {
            for (int j = REACH; j < REACH + 16; j++) {
                int k = i * W + j, x = wx0 + i, z = wz0 + j;
                if (top[k] <= minY) continue;
                // The nearest water column within REACH, and its surface.
                double d = Double.POSITIVE_INFINITY;
                int waterY = 0;
                if (water[k]) {
                    d = 0.0;
                    waterY = top[k];
                } else {
                    for (int a = -REACH; a <= REACH; a++) {
                        for (int c = -REACH; c <= REACH; c++) {
                            int q = (i + a) * W + (j + c);
                            if (!water[q]) continue;
                            double dd = Math.sqrt(a * a + c * c);
                            if (dd < d) { d = dd; waterY = top[q]; }
                        }
                    }
                }
                if (d > REACH) continue;
                Style s = style(level.getBiome(pos.set(x, top[k], z)));
                if (s == null) continue;
                if (plants) plant(level, n, random, s, x, z, top[k], floor[k], water[k], d, waterY, top, water, i, j, pos);
                else dress(level, n, s, x, z, top[k], floor[k], water[k], d, waterY, pos);
            }
        }
        return true;
    }

    /** Whether a block is natural ground this may recolour. */
    private static boolean ground(BlockState st) {
        return st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(Blocks.GRAVEL) || st.is(Blocks.CLAY)
                || st.is(BlockTags.BASE_STONE_OVERWORLD);
    }

    private static Block pick(SimplexNoise n, Block[] blocks, int x, int z, double scale, double salt) {
        double v = 0.5 + 0.5 * n.getValue(x / scale + salt, z / scale - salt);
        return blocks[Math.min(blocks.length - 1, (int) (Math.max(0.0, v) * blocks.length))];
    }

    private static void dress(WorldGenLevel level, SimplexNoise n, Style s, int x, int z, int y, int floorY,
                              boolean wet, double d, int waterY, BlockPos.MutableBlockPos pos) {
        // Ragged ring edges: each column's distance is nudged by a fine noise.
        double jitter = 1.2 * n.getValue(x / 3.0 + 71.0, z / 3.0 - 13.0);
        Block block;
        int depth;
        if (wet) {
            // The bed under the water; in the shallows the waterline material carries on underwater.
            y = floorY;
            boolean shallow = waterY - floorY <= 1;
            block = pick(n, shallow ? s.waterline : s.bed, x, z, shallow ? 5.0 : 9.0, shallow ? 31.0 : 17.0);
            depth = 2;
        } else {
            int above = y - waterY;
            double dj = d + jitter;
            if (above <= 2 && dj <= 2.0) {
                block = pick(n, s.waterline, x, z, 5.0, 31.0);
                depth = 2;
            } else if (above <= 4 && dj <= 5.0) {
                if (0.5 + 0.5 * n.getValue(x / 7.0 - 211.0, z / 7.0 + 97.0) > s.bankShare) return;
                block = pick(n, s.bank, x, z, 6.0, 53.0);
                depth = 1;
            } else if (above <= 6 && dj <= REACH) {
                if (0.5 + 0.5 * n.getValue(x / 5.0 + 401.0, z / 5.0 - 307.0) > s.outerShare) return;
                block = pick(n, s.outer, x, z, 6.0, 89.0);
                depth = 1;
            } else {
                return;
            }
        }
        for (int dy = 0; dy < depth; dy++) {
            pos.set(x, y - dy, z);
            BlockState st = level.getBlockState(pos);
            if (!ground(st)) break;
            BlockState to = block.defaultBlockState();
            // Sand and gravel would fall into a cave under a thin bank; sandstone and stone hold.
            if (dy == depth - 1 && level.getBlockState(pos.below()).isAir()) {
                if (block == Blocks.SAND) to = Blocks.SANDSTONE.defaultBlockState();
                else if (block == Blocks.RED_SAND) to = Blocks.RED_SANDSTONE.defaultBlockState();
                else if (block == Blocks.GRAVEL) to = Blocks.STONE.defaultBlockState();
            }
            if (st != to) level.setBlock(pos, to, 2);
        }
    }

    private static void plant(WorldGenLevel level, SimplexNoise n, RandomSource random, Style s, int x, int z, int y, int floorY,
                              boolean wet, double d, int waterY, int[] top, boolean[] water, int i, int j,
                              BlockPos.MutableBlockPos pos) {
        if (wet) {
            int depth = waterY - floorY;
            if (depth >= 2 && random.nextFloat() < 0.15f) {
                pos.set(x, floorY + 1, z);
                if (!level.getBlockState(pos).is(Blocks.WATER)) return;
                if (depth >= 3 && random.nextFloat() < 0.3f && level.getBlockState(pos.above()).is(Blocks.WATER)) {
                    BlockState lower = Blocks.TALL_SEAGRASS.defaultBlockState();
                    if (lower.canSurvive(level, pos)) {
                        DoublePlantBlock.placeAt(level, lower, pos, 2);
                        return;
                    }
                }
                BlockState sg = Blocks.SEAGRASS.defaultBlockState();
                if (sg.canSurvive(level, pos)) level.setBlock(pos, sg, 2);
            } else if (depth >= 2 && depth <= 5 && 0.5 + 0.5 * n.getValue(x / 9.0 - 613.0, z / 9.0 + 271.0) > 0.62
                    && random.nextFloat() < 0.3f && nearShore(top, water, i, j, 5) && calm(top, water, i, j, waterY)) {
                // Lily pads in patches, in the shallows near the shore.
                pos.set(x, waterY + 1, z);
                BlockState pad = Blocks.LILY_PAD.defaultBlockState();
                if (level.getBlockState(pos).isAir() && pad.canSurvive(level, pos)) level.setBlock(pos, pad, 2);
            }
            return;
        }
        if (y - waterY > 3 || d > 5.0) return;
        pos.set(x, y + 1, z);
        if (!level.getBlockState(pos).isAir()) return;
        if (d <= 1.0 && random.nextFloat() < 0.05f) {
            // Sugar cane at the water's edge, 1 to 3 tall.
            BlockState cane = Blocks.SUGAR_CANE.defaultBlockState();
            if (cane.canSurvive(level, pos)) {
                int h = 1 + random.nextInt(3);
                for (int k = 0; k < h && level.getBlockState(pos).isAir(); k++) {
                    level.setBlock(pos, cane, 2);
                    pos.move(0, 1, 0);
                }
                return;
            }
        }
        if (random.nextFloat() >= 0.22f * (1.0 - d / 6.0)) return;
        if (random.nextFloat() < 0.1f) {
            BlockState tall = (random.nextFloat() < s.ferns ? Blocks.LARGE_FERN : Blocks.TALL_GRASS).defaultBlockState();
            if (tall.canSurvive(level, pos) && level.getBlockState(pos.above()).isAir()) {
                DoublePlantBlock.placeAt(level, tall, pos, 2);
                return;
            }
        }
        BlockState small = (random.nextFloat() < s.ferns ? Blocks.FERN : Blocks.SHORT_GRASS).defaultBlockState();
        if (small.canSurvive(level, pos)) level.setBlock(pos, small, 2);
    }

    /** Whether land (a column that isn't water and isn't empty) is within r blocks. */
    private static boolean nearShore(int[] top, boolean[] water, int i, int j, int r) {
        for (int a = -r; a <= r; a++) {
            for (int c = -r; c <= r; c++) {
                int q = (i + a) * W + (j + c);
                if (a * a + c * c <= r * r && !water[q] && top[q] > Integer.MIN_VALUE / 2) return true;
            }
        }
        return false;
    }

    /** Still lake water: every water column within 3 blocks has the same surface, so no river step is near. */
    private static boolean calm(int[] top, boolean[] water, int i, int j, int waterY) {
        for (int a = -3; a <= 3; a++) {
            for (int c = -3; c <= 3; c++) {
                int q = (i + a) * W + (j + c);
                if (water[q] && top[q] != waterY) return false;
            }
        }
        return true;
    }
}
