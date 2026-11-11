package skyislands;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import org.slf4j.Logger;

/**
 * Shortcuts that make chunk generation in sky-island worlds cheaper without changing what generates. Every one is
 * checked block for block against the normal path (the worldgen lab's /api/fastcheck), and all of them turn off with
 * -Dskyislands.fastgen=false.
 */
public final class FastGen {
    private static final Logger LOG = LogUtils.getLogger();
    private static volatile boolean enabled = !"false".equals(System.getProperty("skyislands.fastgen"));

    /** Counters for the lab and the log: chunks whose structure blending was skipped or kept, cells skipped. */
    public static final AtomicLong BEARD_SKIPPED = new AtomicLong(), BEARD_KEPT = new AtomicLong(),
            CELLS_SKIPPED = new AtomicLong(), CELLS_FILLED = new AtomicLong(), ORES_SKIPPED = new AtomicLong(),
            SECTIONS_DROPPED = new AtomicLong();

    private FastGen() {}

    public static boolean enabled() { return enabled; }

    /**
     * Vanilla's zero structure-blending density (DensityFunctions.BeardifierMarker, which vanilla passes when it samples
     * terrain heights without structures). The type is protected, so it is looked up once by name.
     */
    public static final DensityFunctions.BeardifierOrMarker ZERO_BEARD = zeroBeard();

    private static DensityFunctions.BeardifierOrMarker zeroBeard() {
        try {
            return (DensityFunctions.BeardifierOrMarker) Class.forName("net.minecraft.world.level.levelgen.DensityFunctions$BeardifierMarker").getEnumConstants()[0];
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** For the lab's on/off comparison. */
    public static void setEnabled(boolean on) { enabled = on; }

    // ---- Structure blending (Beardifier) ---------------------------------------------------------------------------

    private static volatile Field[] beardFields;
    private static final Map<String, Boolean> REPORTED = new ConcurrentHashMap<>();

    /**
     * True if this chunk's structure blending adds nothing anywhere, so a zero density can replace it. Vanilla keeps the
     * structure pieces and jigsaw junctions to blend in two lists, and mods add their own fields (YUNG's API and Moog's
     * Structures add lists; RoadWeaver adds a road plan). Every instance field must be null, an empty list or iterator,
     * a primitive, the noise chunk itself, or a RoadWeaver plan with nothing to stamp; any other value counts as
     * "something to blend", so an unknown mod's data keeps blending on.
     */
    public static boolean nothingToBlend(DensityFunctions.BeardifierOrMarker b) {
        if (b == ZERO_BEARD) return true;
        if (!(b instanceof Beardifier)) return false;
        try {
            for (Field f : beardFields()) {
                Object v = f.get(b);
                if (v == null || v instanceof NoiseChunk) continue;
                if (v instanceof ListIterator<?> it) { if (it.hasNext() || it.hasPrevious()) return false; continue; }
                if (v instanceof Iterator<?> it) { if (it.hasNext()) return false; continue; }
                if (v instanceof Collection<?> c) { if (!c.isEmpty()) return false; continue; }
                if (v instanceof Map<?, ?> m) { if (!m.isEmpty()) return false; continue; }
                if (v instanceof Object[] a) { if (a.length > 0) return false; continue; }
                if (ROAD_PLAN.equals(v.getClass().getName())) { if (!roadPlanEmpty(v)) return false; continue; }
                if (REPORTED.putIfAbsent(f.getName(), true) == null) {
                    LOG.info("[skyislands] Beardifier field {} ({}) is set to an unknown kind of value, so chunks with it keep structure blending", f.getName(), v.getClass().getName());
                }
                return false;
            }
            return true;
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    private static final String ROAD_PLAN = "net.shiroha233.roadweaver.worldgen.road.RoadChunkPlan";
    private static volatile java.lang.reflect.Method roadStamp, stampEmpty;

    /**
     * RoadWeaver sets a plan on every Beardifier, and its patch on Beardifier.compute adds road density only when the
     * plan's density stamp isn't empty (RoadWeaver 2.3.2). Anything unexpected counts as not empty.
     */
    private static boolean roadPlanEmpty(Object plan) {
        try {
            if (roadStamp == null) {
                java.lang.reflect.Method m = plan.getClass().getMethod("densityStamp");
                stampEmpty = m.getReturnType().getMethod("isEmpty");
                roadStamp = m;
            }
            Object stamp = roadStamp.invoke(plan);
            return stamp == null || (Boolean) stampEmpty.invoke(stamp);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return false;
        }
    }

    private static synchronized Field[] beardFields() {
        Field[] fs = beardFields;
        if (fs != null) return fs;
        List<Field> out = new ArrayList<>();
        for (Class<?> c = Beardifier.class; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                out.add(f);
            }
        }
        LOG.info("[skyislands] Beardifier fields checked for structure blending: {}", out.stream().map(Field::getName).toList());
        return beardFields = out.toArray(new Field[0]);
    }

    // ---- Which worlds are sky-island worlds ------------------------------------------------------------------------

    private static final Map<ChunkGenerator, Boolean> SKY = new ConcurrentHashMap<>();

    /** True if the generator's terrain comes from SkyTerrain. */
    public static boolean isSky(ChunkGenerator gen) {
        return SKY.computeIfAbsent(gen, g -> {
            if (!(g instanceof NoiseBasedChunkGenerator n)) return false;
            boolean[] found = {false};
            DensityFunction.Visitor finder = df -> {
                if (df instanceof SkyTerrain) found[0] = true;
                return df;
            };
            n.generatorSettings().value().noiseRouter().fluidLevelFloodednessNoise().mapAll(finder);
            return found[0];
        });
    }

    // ---- Decoration in empty sky -----------------------------------------------------------------------------------

    /**
     * The sections whose biomes decide which features a chunk tries: all of them, except a section that is all air with
     * all-air sections above and below. A feature that only those biomes have (cave biomes hanging in the open sky
     * between islands) would start more than 16 blocks from any block, so it would only search empty space.
     */
    public static LevelChunkSection[] decorationSections(ChunkAccess chunk, WorldGenLevel level) {
        LevelChunkSection[] all = chunk.getSections();
        if (!enabled || !isSky(level.getLevel().getChunkSource().getGenerator())) return all;
        int n = all.length, kept = 0;
        boolean[] keep = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (all[i].hasOnlyAir()) continue;
            for (int j = Math.max(0, i - 1); j <= Math.min(n - 1, i + 1); j++) keep[j] = true;
        }
        for (boolean k : keep) if (k) kept++;
        if (kept == n) return all;
        SECTIONS_DROPPED.addAndGet(n - kept);
        LevelChunkSection[] out = new LevelChunkSection[kept];
        for (int i = 0, o = 0; i < n; i++) if (keep[i]) out[o++] = all[i];
        return out;
    }

    private static final Map<OreConfiguration, Boolean> CAN_REPLACE_AIR = new ConcurrentHashMap<>();

    /**
     * True if OreFeature.doPlace, with these arguments, can't place a block: every section its blobs could touch is all
     * air, and none of the ore's targets accepts air. The caller must then draw the random numbers doPlace would have
     * drawn (oreRandomDraws), so later ore attempts in the chunk land exactly where they otherwise would. Only the plain
     * block, block state and tag tests are trusted to answer about air; any other test (random ones, mod ones) counts as
     * "might accept air".
     */
    public static boolean oreBlobInEmptySky(WorldGenLevel level, OreConfiguration cfg, double minX, double maxX,
                                            double minZ, double maxZ, double minY, double maxY) {
        if (!enabled) return false;
        if (CAN_REPLACE_AIR.computeIfAbsent(cfg, FastGen::canReplaceAir)) return false;
        // Each blob is centred on the line from (minX, minY, minZ) to (maxX, maxY, maxZ), with a radius of at most
        // size/16 + 0.5 (doPlace: ((sin + 1) * nextDouble * size/16 + 1) / 2).
        int r = (int) Math.ceil(cfg.size / 16.0) + 2;
        int x0 = (int) Math.floor(Math.min(minX, maxX)) - r, x1 = (int) Math.ceil(Math.max(minX, maxX)) + r;
        int z0 = (int) Math.floor(Math.min(minZ, maxZ)) - r, z1 = (int) Math.ceil(Math.max(minZ, maxZ)) + r;
        int y0 = (int) Math.floor(Math.min(minY, maxY)) - r, y1 = (int) Math.ceil(Math.max(minY, maxY)) + r;
        int minSec = SectionPos.blockToSectionCoord(y0), maxSec = SectionPos.blockToSectionCoord(y1);
        for (int cx = SectionPos.blockToSectionCoord(x0); cx <= SectionPos.blockToSectionCoord(x1); cx++) {
            for (int cz = SectionPos.blockToSectionCoord(z0); cz <= SectionPos.blockToSectionCoord(z1); cz++) {
                if (!level.hasChunk(cx, cz)) return false;
                ChunkAccess c = level.getChunk(cx, cz);
                for (int s = minSec; s <= maxSec; s++) {
                    int i = c.getSectionIndexFromSectionY(s);
                    if (i < 0 || i >= c.getSectionsCount()) continue;
                    if (!c.getSection(i).hasOnlyAir()) return false;
                }
            }
        }
        ORES_SKIPPED.incrementAndGet();
        return true;
    }

    /**
     * The random numbers OreFeature.doPlace (1.21.1) draws when no block it looks at passes a target test: one
     * nextDouble per step of the blob's size. canPlaceOre only draws after a target test passes.
     */
    public static void oreRandomDraws(net.minecraft.util.RandomSource random, OreConfiguration cfg) {
        for (int k = 0; k < cfg.size; k++) random.nextDouble();
    }

    private static boolean canReplaceAir(OreConfiguration cfg) {
        for (OreConfiguration.TargetBlockState t : cfg.targetStates) {
            RuleTest test = t.target;
            Class<?> type = test.getClass();
            boolean plain = type == TagMatchTest.class || type == BlockMatchTest.class || type == BlockStateMatchTest.class;
            if (!plain || test.test(Blocks.AIR.defaultBlockState(), net.minecraft.util.RandomSource.create(0))) return true;
        }
        return false;
    }
}
