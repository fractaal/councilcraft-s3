package skyislands;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;

/**
 * The biome resolver a chunk fills its biomes from, minus the work high in the sky. A chunk's biomes are worked out
 * every 4 blocks from y=-64 to 512, each a full climate and biome-injection lookup (Lithostitched, TerraBlender), and in
 * a sky-island world much of that is open sky above the islands.
 *
 * Above the highest point any island near the column can reach (SkyTerrain.skyEnvelopes, plus MARGIN), or above
 * EMPTY_COLUMN_Y where no island can reach the column, every sample takes the biome of the lowest such sample. That is
 * the same biome vanilla's multi-noise lookup gives: temperature, humidity, continentalness, erosion and weirdness don't
 * vary with height, depth only falls as y rises, and once a surface biome wins on depth it keeps winning higher up.
 * Below that line nothing changes, so cave biomes under and inside islands, and every feature's biome check, are
 * exactly as before. The worldgen lab's /api/fastcheck compares every biome sample with this on and off.
 */
public final class SkyBiomes implements BiomeResolver {
    /** Room for the biome zoom reading a neighbouring sample and for decoration next to land. */
    static final int MARGIN = 40;
    /** Where no island can reach a column: above vanilla's deepest possible underground at the overworld's depth range. */
    static final int EMPTY_COLUMN_Y = 240;

    private final BiomeResolver inner;
    private final SkyTerrain terrain;
    private double[][] env;
    private final int[] line = new int[16];
    @SuppressWarnings("unchecked")
    private final Holder<Biome>[] sky = new Holder[16];

    public SkyBiomes(BiomeResolver inner, SkyTerrain terrain) {
        this.inner = inner;
        this.terrain = terrain;
    }

    @Override
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
        if (env == null) {
            env = terrain.skyEnvelopes((qx >> 2) << 4, (qz >> 2) << 4);
            for (int i = 0; i < 16; i++) {
                double top = Double.NEGATIVE_INFINITY;
                for (int k = 1; k < env[i].length; k += 2) top = Math.max(top, env[i][k]);
                // The quart index of the first sample wholly above the line.
                int y = env[i].length == 0 ? EMPTY_COLUMN_Y : (int) Math.ceil(Math.max(EMPTY_COLUMN_Y, top + MARGIN));
                line[i] = Math.floorDiv(y + 3, 4);
            }
        }
        int i = (qx & 3) << 2 | (qz & 3);
        if (qy < line[i]) {
            FastGen.BIOMES_EXACT.incrementAndGet();
            return inner.getNoiseBiome(qx, qy, qz, sampler);
        }
        FastGen.BIOMES_SKIPPED.incrementAndGet();
        Holder<Biome> b = sky[i];
        if (b == null) sky[i] = b = inner.getNoiseBiome(qx, line[i], qz, sampler);
        return b;
    }
}
