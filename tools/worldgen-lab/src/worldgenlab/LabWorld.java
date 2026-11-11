package worldgenlab;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.material.MapColor;

/**
 * One set of sky-island settings turned into a real chunk generator. Chunks are generated in memory through the same
 * steps a world uses (biomes, noise, surface) and reduced to columns of block runs. Carvers and features (caves, trees,
 * ores) are not run. Each instance keeps its own chunk cache, so changing settings means building a new one.
 */
final class LabWorld {
    static final int WATER = 1 << 24;

    final int version;
    final JsonObject settings;
    final long seed;
    final int minY, height;
    private final NoiseBasedChunkGenerator generator;
    private final RandomState random;
    private final BiomeSource biomeSource;
    private final Registry<Biome> biomes;
    private final LevelHeightAccessor heightAccessor;
    private final StructureManager noStructures;
    private final BiomeManager biomeManager;
    private final ExecutorService pool;
    private final Map<Long, CompletableFuture<Columns>> chunks = new ConcurrentHashMap<>();
    /** Biomes per chunk, shared across versions while the seed and world height stay the same. */
    private static final Map<Long, CompletableFuture<Holder<Biome>[]>> BIOME_CACHE = new ConcurrentHashMap<>();
    private static volatile long biomeCacheId;

    private long cacheId() {
        return seed * 31 + minY * 1009L + height;
    }

    private static int quartIndex(int qx, int qyFromBottom, int qz) {
        return (qyFromBottom * 4 + (qz & 3)) * 4 + (qx & 3);
    }

    /**
     * The chunk's biomes, straight from the biome source as the BIOMES step would place them. The surface step looks
     * up biomes in neighbouring chunks too, and computing those fresh for every lookup was most of the generation time.
     */
    Holder<Biome>[] biomesOf(int cx, int cz) {
        if (biomeCacheId != cacheId()) {
            synchronized (BIOME_CACHE) {
                if (biomeCacheId != cacheId()) { BIOME_CACHE.clear(); biomeCacheId = cacheId(); }
            }
        }
        if (BIOME_CACHE.size() > 60_000) BIOME_CACHE.clear();
        CompletableFuture<Holder<Biome>[]> mine = new CompletableFuture<>();
        CompletableFuture<Holder<Biome>[]> f = BIOME_CACHE.putIfAbsent(ChunkPos.asLong(cx, cz), mine);
        if (f != null) return f.join();
        int quartsY = height >> 2, minQuartY = minY >> 2, qx0 = cx << 2, qz0 = cz << 2;
        try {
            @SuppressWarnings("unchecked") Holder<Biome>[] arr = new Holder[16 * quartsY];
            var sampler = random.sampler();
            for (int qy = 0; qy < quartsY; qy++) for (int qz = 0; qz < 4; qz++) for (int qx = 0; qx < 4; qx++) {
                arr[quartIndex(qx, qy, qz)] = biomeSource.getNoiseBiome(qx0 + qx, minQuartY + qy, qz0 + qz, sampler);
            }
            mine.complete(arr);
            return arr;
        } catch (RuntimeException e) {
            BIOME_CACHE.remove(ChunkPos.asLong(cx, cz), mine);
            mine.completeExceptionally(e);
            throw e;
        }
    }

    /** Builds the noise settings from the datapack's template with these sky-island settings swapped in. */
    LabWorld(MinecraftServer server, JsonObject template, JsonObject settings, int version, ExecutorService pool) {
        this.version = version;
        this.settings = settings;
        this.pool = pool;
        JsonObject json = template.deepCopy();
        int replaced = replaceTerrainSettings(json, settings);
        if (replaced == 0) throw new IllegalStateException("the template has no skyislands:terrain function");
        int noiseTop = settings.has("noiseTop") ? settings.get("noiseTop").getAsInt() : 448;
        json.getAsJsonObject("noise").addProperty("height", noiseTop + 64);

        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        NoiseGeneratorSettings noise = NoiseGeneratorSettings.DIRECT_CODEC.parse(ops, json)
                .getOrThrow(msg -> new IllegalArgumentException("noise settings didn't decode: " + msg));
        this.seed = settings.has("labSeed") ? settings.get("labSeed").getAsLong() : server.getWorldData().worldGenOptions().seed();
        this.biomeSource = server.overworld().getChunkSource().getGenerator().getBiomeSource();
        this.generator = new NoiseBasedChunkGenerator(biomeSource, Holder.direct(noise));
        this.random = RandomState.create(noise, server.registryAccess().lookupOrThrow(Registries.NOISE), seed);
        this.biomes = server.registryAccess().registryOrThrow(Registries.BIOME);
        this.minY = noise.noiseSettings().minY();
        this.height = noise.noiseSettings().height();
        this.heightAccessor = LevelHeightAccessor.create(minY, height);
        this.noStructures = new NoStructures(server);
        int minQuartY = minY >> 2, maxQuartY = minQuartY + (height >> 2) - 1;
        this.biomeManager = new BiomeManager((qx, qy, qz) -> biomesOf(qx >> 2, qz >> 2)[quartIndex(qx, Math.clamp(qy, minQuartY, maxQuartY) - minQuartY, qz)],
                BiomeManager.obfuscateSeed(seed));
    }

    private static int replaceTerrainSettings(JsonElement e, JsonObject settings) {
        int n = 0;
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("type") && o.get("type").isJsonPrimitive() && "skyislands:terrain".equals(o.get("type").getAsString())) {
                o.add("settings", settings.deepCopy());
                n++;
            }
            for (Map.Entry<String, JsonElement> child : o.entrySet()) n += replaceTerrainSettings(child.getValue(), settings);
        } else if (e.isJsonArray()) {
            for (JsonElement child : (JsonArray) e) n += replaceTerrainSettings(child, settings);
        }
        return n;
    }

    /** The seeded terrain function, found the same way the water mixin finds it. */
    skyislands.SkyTerrain terrain() {
        skyislands.SkyTerrain[] t = new skyislands.SkyTerrain[1];
        random.router().fluidLevelFloodednessNoise().mapAll(df -> {
            if (df instanceof skyislands.SkyTerrain s) t[0] = s;
            return df;
        });
        return t[0];
    }

    /** Columns of one chunk, generated once and then shared by every tile and level of detail that needs them. */
    CompletableFuture<Columns> chunk(int cx, int cz) {
        return chunks.computeIfAbsent(ChunkPos.asLong(cx, cz),
                k -> CompletableFuture.supplyAsync(() -> generate(cx, cz), pool));
    }

    /**
     * The world's own generator and random state taken through the same three steps, for checking that the lab builds
     * an identical generator from the template. Uses fresh biomes rather than the cache.
     */
    ProtoChunk generateWithWorldGenerator(MinecraftServer server, int cx, int cz) {
        return generateWithWorldGenerator(server, cx, cz, false);
    }

    /**
     * withStructures: blend the terrain around the real world's structures (and RoadWeaver's roads), whose starts and
     * references the server loads or generates for the chunks around this one.
     */
    ProtoChunk generateWithWorldGenerator(MinecraftServer server, int cx, int cz, boolean withStructures) {
        StructureManager noStructures = withStructures ? server.overworld().structureManager() : this.noStructures;
        var source = server.overworld().getChunkSource();
        var gen = (NoiseBasedChunkGenerator) source.getGenerator();
        RandomState rs = source.randomState();
        ProtoChunk chunk = new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY, server.overworld(), biomes, null);
        gen.createBiomes(rs, Blender.empty(), noStructures, chunk).join();
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        gen.fillFromNoise(Blender.empty(), rs, noStructures, chunk).join();
        chunk.setPersistedStatus(ChunkStatus.NOISE);
        var bs = gen.getBiomeSource();
        gen.buildSurface(chunk, new WorldGenerationContext(gen, server.overworld()), rs, noStructures,
                new BiomeManager((qx, qy, qz) -> bs.getNoiseBiome(qx, qy, qz, rs.sampler()), BiomeManager.obfuscateSeed(seed)),
                biomes, Blender.empty());
        chunk.setPersistedStatus(ChunkStatus.SURFACE);
        return chunk;
    }

    ProtoChunk generateChunk(int cx, int cz) {
        ProtoChunk chunk = new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY, heightAccessor, biomes, null);
        // Statuses as a world would set them; ProtoChunk refuses biome lookups before BIOMES.
        // Biomes come from the template's climate functions, never from the sky-island settings, so each chunk's are
        // computed once and reused by later settings versions and by neighbouring chunks' surface step.
        Holder<Biome>[] own = biomesOf(cx, cz);
        int minQuartY = minY >> 2;
        chunk.fillBiomesFromNoise((qx, qy, qz, sampler) -> own[quartIndex(qx, qy - minQuartY, qz)], random.sampler());
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        generator.fillFromNoise(Blender.empty(), random, noStructures, chunk).join();
        chunk.setPersistedStatus(ChunkStatus.NOISE);
        generator.buildSurface(chunk, new WorldGenerationContext(generator, heightAccessor), random, noStructures,
                biomeManager, biomes, Blender.empty());
        chunk.setPersistedStatus(ChunkStatus.SURFACE);
        return chunk;
    }

    private Columns generate(int cx, int cz) {
        return Columns.of(generateChunk(cx, cz), minY);
    }

    /** Column runs for a chunk: for each of the 256 columns, (bottom y, top y, colour) from the bottom up. */
    static final class Columns {
        final int[] start = new int[257];
        final short[] y0, y1;
        final int[] colour;

        private Columns(int runs) {
            y0 = new short[runs];
            y1 = new short[runs];
            colour = new int[runs];
        }

        static Columns of(ChunkAccess chunk, int minY) {
            LevelChunkSection[] sections = chunk.getSections();
            int maxRuns = 256 * 16;
            short[] ry0 = new short[maxRuns], ry1 = new short[maxRuns];
            int[] rc = new int[maxRuns];
            int[] start = new int[257];
            int n = 0;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int col = z * 16 + x;
                    start[col] = n;
                    int runColour = 0, runStart = 0;
                    boolean inRun = false;
                    for (int s = 0; s < sections.length; s++) {
                        LevelChunkSection section = sections[s];
                        int baseY = minY + s * 16;
                        if (section.hasOnlyAir()) {
                            if (inRun) {
                                if (n == ry0.length) { ry0 = grow(ry0); ry1 = grow(ry1); rc = grow(rc); }
                                ry0[n] = (short) runStart; ry1[n] = (short) (baseY - 1); rc[n++] = runColour;
                                inRun = false;
                            }
                            continue;
                        }
                        for (int dy = 0; dy < 16; dy++) {
                            BlockState state = section.getBlockState(x, dy, z);
                            int y = baseY + dy;
                            int c = state.isAir() ? 0 : colourOf(chunk, state, x, y, z);
                            if (inRun && c == runColour) continue;
                            if (inRun) {
                                if (n == ry0.length) { ry0 = grow(ry0); ry1 = grow(ry1); rc = grow(rc); }
                                ry0[n] = (short) runStart; ry1[n] = (short) (y - 1); rc[n++] = runColour;
                                inRun = false;
                            }
                            if (c != 0) { inRun = true; runStart = y; runColour = c; }
                        }
                    }
                    if (inRun) {
                        if (n == ry0.length) { ry0 = grow(ry0); ry1 = grow(ry1); rc = grow(rc); }
                        ry0[n] = (short) runStart; ry1[n] = (short) (minY + sections.length * 16 - 1); rc[n++] = runColour;
                    }
                }
            }
            start[256] = n;
            Columns c = new Columns(n);
            System.arraycopy(start, 0, c.start, 0, 257);
            System.arraycopy(ry0, 0, c.y0, 0, n);
            System.arraycopy(ry1, 0, c.y1, 0, n);
            System.arraycopy(rc, 0, c.colour, 0, n);
            return c;
        }

        private static short[] grow(short[] a) { return java.util.Arrays.copyOf(a, a.length * 2); }
        private static int[] grow(int[] a) { return java.util.Arrays.copyOf(a, a.length * 2); }
    }

    /** RGB with a flag bit for water. Never 0, so 0 can mean air. Grass, leaves and water take their biome's colour. */
    static int colourOf(ChunkAccess chunk, BlockState state, int x, int y, int z) {
        if (state.is(Blocks.WATER)) {
            Biome b = chunk.getNoiseBiome(x >> 2, y >> 2, z >> 2).value();
            return (b.getWaterColor() & 0xFFFFFF) | WATER;
        }
        if (state.is(Blocks.GRASS_BLOCK)) {
            Biome b = chunk.getNoiseBiome(x >> 2, y >> 2, z >> 2).value();
            return b.getSpecialEffects().getGrassColorOverride().orElse(0x7FB238) | 0x010101;
        }
        if (state.is(BlockTags.LEAVES)) {
            Biome b = chunk.getNoiseBiome(x >> 2, y >> 2, z >> 2).value();
            return b.getSpecialEffects().getFoliageColorOverride().orElse(0x48B518) | 0x010101;
        }
        int rgb;
        try {
            MapColor mc = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            rgb = mc == MapColor.NONE ? 0x9A9A9A : mc.col;
        } catch (RuntimeException e) {
            rgb = 0x9A9A9A;
        }
        return rgb | 0x010101;
    }

    /** The lab generates terrain on its own, so there are never structures to shape the terrain around. */
    private static final class NoStructures extends StructureManager {
        NoStructures(MinecraftServer server) {
            super(server.overworld(), server.getWorldData().worldGenOptions(), null);
        }

        @Override
        public List<StructureStart> startsForStructure(ChunkPos pos, Predicate<Structure> filter) {
            return List.of();
        }

        @Override
        public List<StructureStart> startsForStructure(SectionPos pos, Structure structure) {
            return List.of();
        }
    }
}
