package skyislands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Sky-island terrain as a single density function. The same maths as nested JSON produces a tree of thousands of
 * records that vanilla re-hashes for every chunk; as one object the per-chunk setup cost disappears.
 *
 * <p>Each layer in a column is one top surface and one underside. Everything except the final comparison against y
 * is 2D and computed once per column. Wild regions add one 3D noise on top, and lakes lower a layer's surface to a
 * flat water level chosen once per lake.
 */
public final class SkyTerrain implements DensityFunction {
    public static final MapCodec<SkyTerrain> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            NoiseHolder.CODEC.fieldOf("surface").forGetter(t -> t.surface),
            NoiseHolder.CODEC.fieldOf("vegetation").forGetter(t -> t.vegetation),
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("detail").forGetter(t -> t.detail),
            ExtraCodecs.JSON.fieldOf("settings").forGetter(t -> t.p.json)
    ).apply(i, (s, v, d, j) -> new SkyTerrain(s, v, d, new Params(j), null)));
    private static final KeyDispatchDataCodec<SkyTerrain> CODEC = KeyDispatchDataCodec.of(MAP_CODEC);

    private static final double B = 0.05;
    private static final Lake NO_LAKE = new Lake(0, 0, 0, 0, 0);
    private static final Basin NO_BASIN = new Basin(0, 0, 0, 0, false, -1);
    private static final int UNKNOWN = Integer.MAX_VALUE;
    private static final Aquifer.FluidStatus[] WATER = new Aquifer.FluidStatus[2064];
    /** The last column each thread computed. Corners are visited one column at a time, so this is nearly always a hit. */
    private static final ThreadLocal<Column> LAST = new ThreadLocal<>();

    private final NoiseHolder surface;
    private final NoiseHolder vegetation;
    private final DensityFunction detail;
    private final Params p;
    /** Lake, basin and island decisions per world seed, shared by the per-chunk copies. Null until the noises are seeded. */
    private final Map<Long, Lake> lakes;
    private final Map<Long, Basin> basins;
    private final Map<Long, Island> islands;
    private final long seedMix;

    private static final class Caches {
        final Map<Long, Lake> lakes = new ConcurrentHashMap<>();
        final Map<Long, Basin> basins = new ConcurrentHashMap<>();
        final Map<Long, Island> islands = new ConcurrentHashMap<>();
        final Map<Long, Island> peppers = new ConcurrentHashMap<>();
        final Map<Long, Island[]> pepperTiles = new ConcurrentHashMap<>();
        final Map<Long, Island> leys = new ConcurrentHashMap<>();
    }

    private final Caches caches;

    private SkyTerrain(NoiseHolder surface, NoiseHolder vegetation, DensityFunction detail, Params p, Caches caches) {
        this.surface = surface;
        this.vegetation = vegetation;
        this.detail = detail;
        this.p = p;
        this.caches = caches;
        this.lakes = caches == null ? null : caches.lakes;
        this.basins = caches == null ? null : caches.basins;
        this.islands = caches == null ? null : caches.islands;
        this.seedMix = surface.noise() == null ? 0 : mix(Double.doubleToLongBits(surface.getValue(1234.5, 0, 6789.5)));
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        NoiseHolder s = visitor.visitNoise(surface);
        NoiseHolder v = visitor.visitNoise(vegetation);
        return visitor.apply(new SkyTerrain(s, v, detail.mapAll(visitor), p, caches != null ? caches : new Caches()));
    }

    @Override
    public double compute(FunctionContext c) {
        Column col = LAST.get();
        int x = c.blockX(), z = c.blockZ();
        if (col == null || col.owner != this || col.x != x || col.z != z) {
            col = new Column(this, x, z);
            fill(col, true, true);
            LAST.set(col);
        }
        double y = c.blockY() * B;
        if (col.count == 0) return -B * 64.0;
        double nearest = Double.NEGATIVE_INFINITY;
        for (int l = 0; l < col.count; l++) {
            nearest = Math.max(nearest, Math.min(y - col.bottom[l], col.top[l] - y));
        }
        // Calm columns, and heights over 60 blocks from any surface, never pay for the 3D noise.
        double noise = col.maxK >= 0.0001 && nearest >= -3.0 ? detail.compute(c) : 0.0;
        double best = Double.NEGATIVE_INFINITY;
        for (int l = 0; l < col.count; l++) {
            double k = col.k[l];
            double solid = Math.min(Math.min(y - col.bottom[l], col.top[l] - y), p.interiorCap + 6.0 * (1.0 - k));
            best = Math.max(best, solid + p.strength * k * noise);
        }
        return best;
    }

    private void fill(Column c, boolean withLakes, boolean withBasins) {
        if (p.arch != null) {
            fillArchipelago(c);
            return;
        }
        c.count = p.layers;
        final double x = c.x, z = c.z;
        double g = clamp(p.gap * (1.0 + 2.0 * p.merging * veg(x * p.gapScale - 2311.0, z * p.gapScale + 877.0)), 0.0, p.maxGap);
        double wl = clamp(g / Math.max(1.0, p.gap), 0.0, 1.0);
        double alt0 = veg(x * p.driftScale + 37.0, z * p.driftScale + 53.0);
        double wild = clamp((veg(x * p.wildScale + p.wildShiftX, z * p.wildScale + p.wildShiftZ) - p.wildThreshold) / p.wildFade, 0.0, 1.0);
        double region = p.openSky > 0 ? p.openSky * veg(x * 0.04 + 911.0, z * 0.04 - 1733.0) : 0.0;
        double grow = 1.0 + p.thicken * wild;
        // Plateau regions: bigger, smoother islands with flat tops and cliff edges. Wild regions take precedence.
        double plat = p.plateauScale > 0
                ? clamp((veg(x * p.plateauScale + p.plateauShiftX, z * p.plateauScale + p.plateauShiftZ) - p.plateauThreshold) / p.plateauFade, 0.0, 1.0) * (1.0 - wild)
                : 0.0;
        double plateauRise = plat > 0 ? p.plateauHeight * (1.0 + p.plateauVariety * veg(x * 0.05 + 3001.0, z * 0.05 - 4007.0)) : 0.0;
        double smooth = 1.0 - plat * p.plateauSmooth;
        double flatten = 1.0 - plat * p.plateauFlat;
        double cutoff = p.cutoff - plat * p.plateauGrow;
        c.wild = wild;
        c.plateau = plat;
        double maxK = 0.0;
        for (int l = 0; l < p.layers; l++) {
            double v = p.weights[0] * surf(x * p.bandScale[0] + 1013.0 + 7001.0 * l, z * p.bandScale[0] - 877.0 - 5003.0 * l) + region;
            for (int o = 1; o <= 3; o++) {
                double term = p.weights[o] * surf(x * p.bandScale[o] + 1013.0 * (o + 1) + 7001.0 * l, z * p.bandScale[o] - 877.0 * (o + 1) - 5003.0 * l);
                if (o >= 2) term *= smooth;
                double e = clamp((v + p.coastWidth - cutoff) / (2.0 * p.coastWidth), 0.0, 1.0);
                v = v + Math.max(term, 0.0) + Math.min(term, 0.0) * (1.0 - p.fillHoles * e);
            }
            double above = v - cutoff;
            double mr = clamp(above / p.taper, -1.0, 1.0);
            double step = clamp(100.0 * above, -1.0, 1.0);
            double inner = clamp(4.0 * mr, 0.0, 1.0);
            double outer = 1.0 - clamp(2.5 * mr, 0.0, 1.0);
            double altL = l > 0 ? veg(x * p.driftScale + 1409.0 * l + 37.0, z * p.driftScale - 1103.0 * l + 53.0) : alt0;
            double drift = p.heightSpread * (alt0 * (1.0 - wl) + altL * wl) * (1.0 - plat * p.plateauLevel);
            double jitter = p.smallScatter * surf(x * 0.26 + 811.0 * l + 13.0, z * 0.26 - 607.0 * l + 29.0) * outer * flatten;
            double yc = 160.0 + p.layerOffset[l] * g + drift + jitter;
            double hill = 12.0 * (1.0 + p.hills * wild) * surf(x * 0.45 + 433.0 * l + 71.0, z * 0.45 - 599.0 * l + 97.0) * inner * flatten;
            double bump = 10.0 * surf(x * 1.1 + 521.0 * l + 113.0, z * 1.1 - 673.0 * l + 131.0) * inner * flatten;
            double top = yc + p.dome * mr * grow + 2.0 * step + hill;
            double bottom = yc - p.underside * mr * grow - 4.0 * step + bump;
            if (plat > 0) {
                // A mesa: full height a short way in from the coast, then flat. Terraces quantise the rise into steps.
                double rise = plateauRise * clamp(mr * p.plateauCliff, 0.0, 1.0);
                if (p.plateauTerrace > 0) rise = Math.floor(rise / p.plateauTerrace) * p.plateauTerrace;
                double mesaTop = yc + 2.0 * step + rise + hill;
                top += (mesaTop - top) * plat;
                bottom -= plat * p.plateauUnderside * clamp(mr, 0.0, 1.0);
            }
            c.yc[l] = yc - jitter;
            c.basinLevel[l] = Double.NaN;
            if (withBasins && p.basinCell > 0) {
                double[] tb = c.scratch;
                tb[0] = top;
                tb[1] = bottom;
                c.basinLevel[l] = applyBasin(c.x, c.z, l, tb);
                top = tb[0];
                bottom = tb[1];
            }
            if (withLakes && p.lakeCell > 0) {
                top = shapeAroundLake(c.x, c.z, l, top);
            }
            c.mr[l] = mr;
            c.top[l] = B * top;
            c.bottom[l] = B * bottom;
            c.k[l] = wild * clamp(mr + 1.0, 0.0, 1.0);
            maxK = Math.max(maxK, c.k[l]);
        }
        c.maxK = maxK;
    }

    // ---- Archipelago mode -----------------------------------------------------------------------------------------

    /**
     * One island, decided once from its grid cell. Its "genes" (size, lobes, height, how far its middle dips, rim,
     * roughness, steps, peak) are rolled per island and nudged by slow regional fields, so neighbours resemble each
     * other. Lakes, islets, cliffs and atoll or caldera shapes all come out of those numbers, not separate cases.
     * Lobes are {x, z, radius, height offset} in the island's own stretched frame, in units of r; lobe 0 is the
     * centre. Lobes at different heights make multi-tier islands with cliffs where the tiers meet.
     */
    private record Island(double x, double z, double r, double cos, double sin, double sqrtAspect, double[] lobes,
                          double base, double dome, double hills, double bowlDepth, double bowlWidth, double rim,
                          double waterLevel, double terrace, double peak, double under, double edge, double ox, double oz,
                          double reach, double coastReach, double cliff, double wild) {}

    private static final Island NO_ISLAND = new Island(0, 0, 0, 1, 0, 1, new double[0], 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    private static final int MAIN = 0, LEY = 1, PEPPER = 2;

    // Cache keys are packed cell coordinates run through mix(), which is one-to-one, so keys stay unique. Unmixed, the
    // packed z bits sit where ConcurrentHashMap's small tables ignore them, and a whole column of cells shares a bin.
    private Island mainIsland(int cellX, int cellZ) {
        long key = mix((cellX & 0x3FFFFFFL) | (cellZ & 0x3FFFFFFL) << 26);
        Local l = local();
        int i = Local.slot(key);
        if (l.keys[MAIN][i] == key && l.vals[MAIN][i] != null) return (Island) l.vals[MAIN][i];
        Island is = islands.computeIfAbsent(key, k -> buildIsland(cellX, cellZ, MAIN));
        l.keys[MAIN][i] = key;
        l.vals[MAIN][i] = is;
        return is;
    }

    private Island leyIsland(int cellX, int cellZ) {
        long key = mix((cellX & 0x3FFFFFFL) | (cellZ & 0x3FFFFFFL) << 26);
        Local l = local();
        int i = Local.slot(key);
        if (l.keys[LEY][i] == key && l.vals[LEY][i] != null) return (Island) l.vals[LEY][i];
        Island is = caches.leys.computeIfAbsent(key, k -> buildIsland(cellX, cellZ, LEY));
        l.keys[LEY][i] = key;
        l.vals[LEY][i] = is;
        return is;
    }

    private Island pepperIsland(int cellX, int cellZ) {
        long key = mix((cellX & 0x3FFFFFFL) | (cellZ & 0x3FFFFFFL) << 26);
        Local l = local();
        int i = Local.slot(key);
        if (l.keys[PEPPER][i] == key && l.vals[PEPPER][i] != null) return (Island) l.vals[PEPPER][i];
        // A separate map: building an islet looks up main islands, and a map can't be updated from inside its own update.
        Island is = caches.peppers.computeIfAbsent(key, k -> buildIsland(cellX, cellZ, PEPPER));
        l.keys[PEPPER][i] = key;
        l.vals[PEPPER][i] = is;
        return is;
    }

    /**
     * A small per-thread copy of recent island lookups in front of the shared maps. A column looks up dozens of cells
     * and neighbouring columns repeat them, and each shared-map lookup boxes its key and hashes into a concurrent table.
     * Entries are only ever copies of what the shared maps hold, so this can't change an answer.
     */
    private static final class Local {
        static final int SIZE = 2048, TILE = 3;
        Caches owner;
        final long[][] keys = new long[4][SIZE];
        final Object[][] vals = new Object[4][SIZE];

        static int slot(long key) { return (int) (key ^ key >>> 32) & (SIZE - 1); }
    }

    private static final ThreadLocal<Local> LOCAL = ThreadLocal.withInitial(Local::new);

    private Local local() {
        Local l = LOCAL.get();
        if (l.owner != caches) {
            for (Object[] v : l.vals) java.util.Arrays.fill(v, null);
            l.owner = caches;
        }
        return l;
    }

    private static final int PEPPER_TILE = 64;

    /**
     * The islets whose reach overlaps a PEPPER_TILE-wide square, in cell order. Islets can land several cells from
     * their own, so the search is wide; the columns of a tile share one scan instead of each repeating it.
     */
    private Island[] pepperTile(int tx, int tz) {
        long key = mix((tx & 0x3FFFFFFL) | (tz & 0x3FFFFFFL) << 26);
        Local l = local();
        int i = Local.slot(key);
        if (l.keys[Local.TILE][i] == key && l.vals[Local.TILE][i] != null) return (Island[]) l.vals[Local.TILE][i];
        Island[] tile = caches.pepperTiles.get(key);
        if (tile == null) tile = caches.pepperTiles.computeIfAbsent(key, k -> buildPepperTile(tx, tz));
        l.keys[Local.TILE][i] = key;
        l.vals[Local.TILE][i] = tile;
        return tile;
    }

    private Island[] buildPepperTile(int tx, int tz) {
        Arch a = p.arch;
        int cell = (int) a.pepperCell, s = a.pepperSearch;
        int x0 = tx * PEPPER_TILE, z0 = tz * PEPPER_TILE, x1 = x0 + PEPPER_TILE - 1, z1 = z0 + PEPPER_TILE - 1;
        java.util.List<Island> out = new java.util.ArrayList<>();
        for (int px = Math.floorDiv(x0, cell) - s; px <= Math.floorDiv(x1, cell) + s; px++) {
            for (int pz = Math.floorDiv(z0, cell) - s; pz <= Math.floorDiv(z1, cell) + s; pz++) {
                Island is = pepperIsland(px, pz);
                if (is == NO_ISLAND) continue;
                // shapeRn's reach test against the nearest point of the tile, with room for any shared warp.
                double dx = Math.max(0.0, Math.max(x0 - is.x, is.x - x1)), dz = Math.max(0.0, Math.max(z0 - is.z, is.z - z1));
                double room = is.reach + a.sharedRoom();
                if (dx * dx + dz * dz < room * room) out.add(is);
            }
        }
        return out.toArray(new Island[0]);
    }

    /** The ley field: one slow noise plus a little detail so the lines meander. */
    private double ley(double x, double z) {
        double s = p.arch.leyScale;
        return veg(x * s + 1777.0, z * s - 919.0) + 0.3 * veg(x * s * 2.3 - 3301.0, z * s * 2.3 + 4517.0);
    }

    /** A regional field in roughly [0, 1], constant over a few thousand blocks. */
    private double region(double x, double z, double salt) {
        return clamp(0.5 + 0.6 * veg(x * p.arch.regionScale + salt, z * p.arch.regionScale - 1.7 * salt), 0.0, 1.0);
    }

    /**
     * Three populations: MAIN islands are the big roundish backbone and ignore the ley lines; LEY islands are smaller,
     * snap onto the lines and stretch along them (only where a slow field says lines are strong); PEPPER islets
     * cluster along the coasts of both.
     */
    private Island buildIsland(int cellX, int cellZ, int kind) {
        Arch a = p.arch;
        boolean pepper = kind == PEPPER, leyKind = kind == LEY;
        double cell = pepper ? a.pepperCell : leyKind ? a.leyCell : a.cell;
        long salt = pepper ? 0x7F4A7C159E3779B9L : leyKind ? 0x5851F42D4C957F2DL : 0x3C6EF372FE94F82BL;
        long h = mix(seedMix ^ cellX * 0x9E3779B97F4A7C15L ^ cellZ * 0xC2B2AE3D27D4EB4FL ^ salt);
        double cx = (cellX + 0.5 + a.jitter * (unit(h = mix(h)) - 0.5)) * cell;
        double cz = (cellZ + 0.5 + a.jitter * (unit(h = mix(h)) - 0.5)) * cell;
        double lakeRegion = region(cx, cz, 101.0), rugged = region(cx, cz, 3001.0), mesa = region(cx, cz, 4441.0);
        double sizeRegion = region(cx, cz, 811.0), pepperRegion = region(cx, cz, 5507.0);
        double band = a.baseY + a.regionDrift * (2.0 * region(cx, cz, 2203.0) - 1.0);
        double chance = leyKind ? a.leyChance : a.chance;
        // Open-sky regions: main islands thin out to a scattering, leaving air with islets between dense districts.
        if (kind == MAIN && a.openSky > 0) chance *= 1.0 - a.openSky * (1.0 - smoothstep(0.25, 0.65, region(cx, cz, 9001.0)));
        // Ley lines: contour lines of one slow field. Centres are pulled onto the nearest line, islands are likelier
        // close to a line, stretch along it, and take the line's height, so they string out along flowing curves.
        double lineAngle = Double.NaN, onLine = 1.0;
        if (a.leySpacing > 0 && kind != MAIN) {
            double e = 8.0, f = ley(cx, cz);
            double gx = (ley(cx + e, cz) - ley(cx - e, cz)) / (2.0 * e), gz = (ley(cx, cz + e) - ley(cx, cz - e)) / (2.0 * e);
            double g2 = gx * gx + gz * gz;
            if (g2 > 1e-14) {
                double k = Math.rint(f / a.leySpacing), delta = f - k * a.leySpacing;
                // The move is capped at one cell: where the field is nearly flat the nearest line is far away, and an
                // uncapped pull would fling the island out of reach of the cell search (it used to clip islets).
                double pull = pepper ? a.leyPullPepper : a.leyPull;
                double toLine = Math.abs(delta) / Math.sqrt(g2), move = Math.min(pull * toLine, cell);
                double step = move * Math.signum(delta) / Math.sqrt(g2);
                cx -= step * gx;
                cz -= step * gz;
                double dist = toLine - move;
                double width = pepper ? a.leyPepperBand : a.leyBand;
                onLine = Math.exp(-(dist * dist) / (width * width));
                // Islets don't need the band: they cluster along the coasts of the chains, which already follow the lines.
                if (leyKind) {
                    chance *= a.leyOff + (1.0 - a.leyOff) * onLine;
                    // Some stretches of line are full ridges, others break up into dashes.
                    chance *= (1.0 - a.leyGaps) + a.leyGaps * smoothstep(0.3, 0.7, region(cx, cz, 6151.0));
                }
                lineAngle = Math.atan2(gx, -gz);
                band += a.lineHeightSpread * (2.0 * unit(mix(seedMix ^ (long) k * 0xD1B54A32D192ED03L)) - 1.0);
            }
        }
        double nearBase = Double.NaN;
        if (pepper) {
            // Islets gather along the coasts of the big islands and thin out in open sky.
            double best = Double.POSITIVE_INFINITY;
            double[] sw = sharedWarp(cx, cz);
            int mx = (int) Math.floor(cx / a.cell), mz = (int) Math.floor(cz / a.cell);
            for (int dx = -a.coastSearch; dx <= a.coastSearch; dx++) for (int dz = -a.coastSearch; dz <= a.coastSearch; dz++) {
                Island m = mainIsland(mx + dx, mz + dz);
                if (m == NO_ISLAND) continue;
                double rn = coastRn(m, cx, cz, sw);
                if (rn < best) { best = rn; nearBase = m.base; }
            }
            int lx = (int) Math.floor(cx / a.leyCell), lz = (int) Math.floor(cz / a.leyCell);
            if (a.leySpacing > 0) for (int dx = -a.leyCoastSearch; dx <= a.leyCoastSearch; dx++) for (int dz = -a.leyCoastSearch; dz <= a.leyCoastSearch; dz++) {
                Island m = leyIsland(lx + dx, lz + dz);
                if (m == NO_ISLAND) continue;
                double rn = coastRn(m, cx, cz, sw);
                if (rn < best) { best = rn; nearBase = m.base; }
            }
            if (best < 1.0) return NO_ISLAND;
            double near = 1.0 - smoothstep(1.0, 1.0 + a.pepperCluster, best);
            chance *= a.pepperChance / Math.max(1e-9, a.chance) * (0.25 + 1.5 * pepperRegion) * (0.25 + 2.5 * near);
            if (Double.isNaN(nearBase) || near <= 0.0) nearBase = Double.NaN;
        }
        if (unit(h = mix(h)) >= chance) return NO_ISLAND;
        double u = unit(h = mix(h));
        double r = pepper ? a.pepperMinRadius + (a.pepperMaxRadius - a.pepperMinRadius) * Math.pow(u, 2.2)
                : leyKind ? a.leyMinRadius + (a.leyMaxRadius - a.leyMinRadius) * Math.pow(u, 1.6)
                : (a.minRadius + (a.maxRadius - a.minRadius) * Math.pow(u, a.sizeSkew)) * (0.75 + 0.5 * sizeRegion);
        double angle = unit(h = mix(h)) * Math.PI;
        if (leyKind && !Double.isNaN(lineAngle) && unit(h = mix(h)) < a.leyAlign) angle = lineAngle + 0.35 * (unit(h = mix(h)) - 0.5);
        if (kind == MAIN && a.flowAlign > 0 && unit(h = mix(h)) < a.flowAlign) {
            // The grain: main islands point along the flow (the ley field's contour direction) where they are.
            double e = 16.0;
            double gx = ley(cx + e, cz) - ley(cx - e, cz), gz = ley(cx, cz + e) - ley(cx, cz - e);
            if (gx * gx + gz * gz > 1e-18) angle = Math.atan2(gx, -gz) + 0.5 * (unit(h = mix(h)) - 0.5);
        }
        double aspect = (1.0 + a.elongation * unit(h = mix(h))) * (leyKind ? 1.0 + a.leyStretch * onLine : 1.0);
        double spread = pepper ? a.pepperHeightSpread : a.heightSpread;
        double centre = pepper && !Double.isNaN(nearBase) ? nearBase : band;
        double base = Math.floor(centre + spread * (2.0 * unit(h = mix(h)) - 1.0));
        double ox = (unit(h = mix(h)) - 0.5) * 40000.0, oz = (unit(h = mix(h)) - 0.5) * 40000.0;
        // Lobes: the centre plus up to a.lobes more, overlapping it, which makes bays and peninsulas.
        int extra = pepper ? (unit(h = mix(h)) < 0.3 ? 1 : 0) : (int) Math.floor(unit(h = mix(h)) * (a.lobes + 1));
        double[] lobes = new double[4 * (1 + extra)];
        lobes[2] = 1.0;
        double far = 1.0;
        for (int k = 1; k <= extra; k++) {
            double la = unit(h = mix(h)) * 2.0 * Math.PI, ld = 0.35 + 0.45 * unit(h = mix(h)), lr = 0.4 + 0.4 * unit(h = mix(h));
            lobes[4 * k] = Math.cos(la) * ld;
            lobes[4 * k + 1] = Math.sin(la) * ld;
            lobes[4 * k + 2] = lr;
            far = Math.max(far, ld + lr);
        }
        double reach = a.reach(r, far * Math.sqrt(aspect), 1.0);
        double coastReach = a.reach(r, far * Math.sqrt(aspect), a.coastBand());
        double sqrtAspect = Math.sqrt(aspect);
        // Every island, islets included, rolls the same genes. Size decides how they show: a small island that rolls
        // a peak becomes a floating spike, because peaks scale with the square root of size and roots with height.
        double sizeScale = Math.sqrt(r / (0.5 * (a.minRadius + a.maxRadius)));
        double dome = a.dome * (0.4 + 1.2 * unit(h = mix(h))) * sizeScale;
        double hills = a.hills * (0.25 + 2.2 * rugged * rugged * unit(h = mix(h))) * Math.min(1.0, r / 40.0);
        boolean bowl = r >= a.bowlMinRadius && unit(h = mix(h)) < a.bowlChance * (0.3 + 1.4 * lakeRegion);
        double bowlDepth = bowl ? a.bowlDepthMin + (a.bowlDepthMax - a.bowlDepthMin) * unit(h = mix(h)) : 0.0;
        double bowlWidth = a.bowlWidthMin + (a.bowlWidthMax - a.bowlWidthMin) * unit(h = mix(h));
        double rim = bowl ? a.rimMax * Math.pow(unit(h = mix(h)), 1.5) : 0.0;
        // Water sits a little under the lowest likely shore, so lakes stay inside their island most of the time.
        double waterLevel = bowl ? base + Math.floor(Math.min(a.waterline, 0.5 * dome + 0.6 * rim)) : Double.NaN;
        double terrace = unit(h = mix(h)) < a.terraceChance * (0.2 + 1.6 * mesa) ? a.terraceStep : 0.0;
        // Lake islands can be mountainous too; then the mountains ring the lake instead of filling the middle.
        double peakOdds = bowl ? a.hybridPeak * (0.2 + 1.6 * rugged) : a.peakChance * (0.2 + 1.6 * rugged);
        double peak = unit(h = mix(h)) < peakOdds
                ? (a.peakMin + (a.peakMax - a.peakMin) * unit(h = mix(h))) * Math.sqrt(r / a.maxRadius) : 0.0;
        // Lake islands: land rising to a cliff over the water on some sides.
        double cliff = bowl ? a.cliffRim * Math.pow(unit(h = mix(h)), 0.7) : 0.0;
        // Crags: the 3D detail noise on this island (overhangs, caves in cliff faces).
        double wild = unit(h = mix(h)) < a.wildChance * (0.2 + 1.6 * rugged) ? a.wildStrength * (0.5 + 0.5 * unit(h = mix(h))) : 0.0;
        // Tiers: each lobe at its own height (not on lake islands, whose water has one level).
        if (!bowl && unit(h = mix(h)) < a.tierChance * (0.3 + 1.4 * rugged)) {
            for (int k = 1; k < lobes.length / 4; k++) lobes[4 * k + 3] = Math.rint((2.0 * unit(h = mix(h)) - 1.0) * a.tierMax);
        }
        double under = (pepper ? a.pepperUnderside : a.underside) * (0.7 + 0.6 * unit(h = mix(h)));
        double edge = Math.max(3.0, a.edgeThickness * (0.6 + 0.8 * unit(h = mix(h))) * sizeScale);
        return new Island(cx, cz, r, Math.cos(angle), Math.sin(angle), sqrtAspect, lobes, base, dome, hills, bowlDepth,
                bowlWidth, rim, waterLevel, terrace, peak, under, edge, ox, oz, reach, coastReach, cliff, wild);
    }

    private void fillArchipelago(Column c) {
        c.count = 0;
        c.maxK = 0.0;
        c.wild = 0.0;
        c.channel = Double.NaN;
        c.sharedWarp = sharedWarp(c.x, c.z);
        addMainIslands(c, c.x, c.z);
        Arch a = p.arch;
        if (a.leySpacing > 0) {
            int lx = (int) Math.floor(c.x / a.leyCell), lz = (int) Math.floor(c.z / a.leyCell);
            for (int dx = -a.leySearch; dx <= a.leySearch; dx++) for (int dz = -a.leySearch; dz <= a.leySearch; dz++) {
                Island is = leyIsland(lx + dx, lz + dz);
                if (is != NO_ISLAND) addIsland(c, is, c.x, c.z);
            }
        }
        if (a.pepperChance > 0) {
            for (Island is : pepperTile(Math.floorDiv(c.x, PEPPER_TILE), Math.floorDiv(c.z, PEPPER_TILE))) addIsland(c, is, c.x, c.z);
        }
    }

    private void addMainIslands(Column c, double x, double z) {
        Arch a = p.arch;
        int cx = (int) Math.floor(x / a.cell), cz = (int) Math.floor(z / a.cell);
        for (int dx = -a.search; dx <= a.search; dx++) for (int dz = -a.search; dz <= a.search; dz++) {
            Island is = mainIsland(cx + dx, cz + dz);
            if (is != NO_ISLAND) addIsland(c, is, x, z);
        }
    }

    /** Noise with features about `wavelength` blocks across, offset per island so islands don't share patterns. */
    private double sn(double x, double z, double wavelength, double ox, double oz) {
        double s = 64.0 / wavelength;
        return surf(x * s + ox, z * s + oz);
    }

    /**
     * shapeRn for the islet coast query, which needs values past 1 (islets cluster where it is under coastBand()), so it
     * has its own cutoff: beyond coastReach the value is at least coastBand() and counts as far away.
     */
    private double coastRn(Island is, double x, double z, double[] sw) {
        double wx = x - is.x + sw[0], wz = z - is.z + sw[1];
        if (wx * wx + wz * wz >= is.coastReach * is.coastReach) return Double.POSITIVE_INFINITY;
        return shapeMaths(is, x, z, sw, null);
    }

    /**
     * One warp for the whole map, so neighbouring islands bend the same way and their shores follow each other. It
     * depends only on the point, so callers work it out once per point and pass it to shapeRn for every island.
     */
    private double[] sharedWarp(double x, double z) {
        Arch a = p.arch;
        if (a.sharedWarp <= 0) return NO_WARP;
        return new double[]{a.sharedWarp * sn(x, z, a.sharedWarpWidth, 7717.0, -3313.0),
                a.sharedWarp * sn(x, z, a.sharedWarpWidth, -5521.0, 8803.0)};
    }

    private static final double[] NO_WARP = {0.0, 0.0};

    /**
     * How far (x, z) is inside the island: below 1 is land, 0 is the middle, and points that can't be land give
     * infinity. sw is sharedWarp(x, z); tierOut[0] receives the height offset of the lobe this point belongs to.
     */
    private double shapeRn(Island is, double x, double z, double[] sw, double[] tierOut) {
        double dx = x - is.x, dz = z - is.z;
        // The shared warp moves the point by the same amount for every island, so it is applied before the reach
        // test; then each island's reach only has to cover its own shape, and far islands are skipped cheaply.
        double wx = dx + sw[0], wz = dz + sw[1];
        if (wx * wx + wz * wz >= is.reach * is.reach) return Double.POSITIVE_INFINITY;
        return shapeMaths(is, x, z, sw, tierOut);
    }

    /**
     * The shape value itself. The point is first pushed around by a domain warp (curving bays and peninsulas) and the
     * shared warp, then measured against the nearest lobe, then the shoreline is roughened.
     */
    private double shapeMaths(Island is, double x, double z, double[] sw, double[] tierOut) {
        double dx = x - is.x, dz = z - is.z;
        Arch a = p.arch;
        double wl = 0.9 * is.r + 10.0;
        dx += a.domainWarp * is.r * sn(x, z, wl, is.ox + 5113.0, is.oz - 2707.0);
        dz += a.domainWarp * is.r * sn(x, z, wl, is.oz - 6029.0, is.ox + 3361.0);
        if (a.sharedWarp > 0) {
            dx += sw[0];
            dz += sw[1];
        }
        double u = (dx * is.cos + dz * is.sin) / (is.sqrtAspect * is.r), v = (-dx * is.sin + dz * is.cos) * is.sqrtAspect / is.r;
        double d = Double.POSITIVE_INFINITY;
        double[] L = is.lobes;
        double tier = 0.0;
        for (int k = 0; k < L.length; k += 4) {
            double lu = u - L[k], lv = v - L[k + 1];
            double dk = Math.sqrt(lu * lu + lv * lv) / L[k + 2];
            if (dk < d) { d = dk; tier = L[k + 3]; }
        }
        if (tierOut != null) tierOut[0] = tier;
        return d + a.coastWarp * (sn(x, z, 0.8 * is.r + 8.0, is.ox, is.oz)
                + 0.5 * sn(x, z, 0.3 * is.r + 6.0, is.oz, is.ox) + 0.25 * sn(x, z, 0.12 * is.r + 5.0, is.ox + 77.0, is.oz - 91.0));
    }

    /**
     * Channels: the ley lines live in the negative space. Each contour line of the ley field is a channel of air that
     * pushes land away, so islands it crosses are cut along it and the coasts on both sides run parallel. Width varies
     * along the line, and in some stretches the channel fades out and islands join across it. 0 = none, 1 = core.
     */
    private double channel(Column c, double x, double z) {
        if (!Double.isNaN(c.channel)) return c.channel;
        Arch a = p.arch;
        double v = 0.0;
        if (a.channelWidth > 0 && a.leySpacing > 0) {
            double e = 4.0, f = ley(x, z);
            double gx = (ley(x + e, z) - f) / e, gz = (ley(x, z + e) - f) / e;
            double g = Math.sqrt(gx * gx + gz * gz);
            if (g > 1e-9) {
                double k = Math.rint(f / a.leySpacing);
                double dist = Math.abs(f - k * a.leySpacing) / g;
                double width = a.channelWidth * (1.0 + a.channelWidthVar * sn(x, z, 380.0, 1201.0, -877.0));
                double present = 1.0 - a.leyGaps * (1.0 - smoothstep(0.3, 0.6, region(x, z, 6151.0 + 31.0 * k)));
                v = smoothstep(0.0, 1.0, clamp(1.0 - dist / Math.max(4.0, width), 0.0, 1.0)) * present;
            }
        }
        c.channel = v;
        return v;
    }

    /** Adds this island's solid span at (x, z) to the column, if its land reaches here. */
    private void addIsland(Column c, Island is, double x, double z) {
        if (c.count >= c.top.length) return;
        double[] tierOut = new double[1];
        double rn = shapeRn(is, x, z, c.sharedWarp, tierOut);
        if (rn >= 1.0) return;  // channels only ever remove land
        Arch a = p.arch;
        if (a.channelWidth > 0) {
            boolean islet = is.r <= a.pepperMaxRadius && is.bowlDepth == 0 && is.edge <= 3.0;
            // Channels erode coasts and narrow necks but can't cut through the heart of a big island.
            double heart = smoothstep(0.35, 0.8, 1.0 - rn) * smoothstep(40.0, 120.0, is.r);
            rn += (islet ? a.channelPepper : a.channelStrength) * channel(c, x, z) * (1.0 - a.channelCoreResist * heart);
        }
        if (rn >= 1.0) return;
        double t = 1.0 - rn;
        double ground = is.base + tierOut[0];
        double top = ground + is.dome * smoothstep(0.0, 0.6, t);
        double rise = smoothstep(0.0, 0.2, t);
        double inside = is.bowlDepth > 0
                ? t - (1.0 - is.bowlWidth) + a.shoreWarp * sn(x, z, 0.4 * is.r + 8.0, is.ox - 919.0, is.oz + 457.0) : 0.0;
        top += is.hills * (sn(x, z, Math.max(32.0, 0.6 * is.r), is.ox + 711.0, is.oz - 333.0)
                + 0.45 * sn(x, z, Math.max(14.0, 0.22 * is.r), is.oz + 211.0, is.ox - 919.0)) * rise;
        if (is.peak > 0) {
            // Ridged noise: sharp crests where the noise crosses zero, so peaks come as ranges rather than cones.
            double ridge = 1.0 - Math.abs(sn(x, z, 0.45 * is.r + 10.0, is.oz + 97.0, is.ox - 41.0));
            // On a lake island the mountains ring the water: strongest just outside the lake, none inside it.
            double where = is.bowlDepth > 0
                    ? smoothstep(-0.45, -0.08, inside) * (1.0 - smoothstep(-0.08, 0.0, inside))
                    : Math.pow(smoothstep(0.0, 1.0, t), 1.3);
            top += is.peak * where * (0.35 + 0.85 * ridge * ridge);
        }
        double level = Double.NaN;
        if (is.bowlDepth > 0) {
            // Where the middle dips below the water level, there's lake. Noise moves the shore and lifts islets.
            if (is.cliff > 0) {
                // Land rising towards the water on some sides, so it ends in a cliff over the lake there.
                double side = clamp(0.35 + 1.3 * sn(x, z, 0.7 * is.r + 10.0, is.ox + 3203.0, is.oz - 2741.0), 0.0, 1.0);
                top += is.cliff * side * smoothstep(-0.35, -0.04, inside) * (1.0 - smoothstep(-0.03, 0.01, inside));
            }
            double rimBump = is.rim * Math.exp(-((inside + 0.06) * (inside + 0.06)) / 0.004);
            top += rimBump;
            // Inside the lake area the ground blends down to a bed set from the water line (deeper towards the
            // middle), so a tall island's dome or hills can't leave the lake dry. The steeper the blend, the more the
            // shore is a cliff. Islets are where the bed noise pokes above the water.
            double bed = is.waterLevel - 1.0 - is.bowlDepth * smoothstep(0.0, 0.45, inside)
                    + 2.0 * sn(x, z, 0.25 * is.r + 8.0, is.ox + 1709.0, is.oz - 643.0);
            double islet = sn(x, z, 0.18 * is.r + 8.0, is.oz + 2201.0, is.ox - 1301.0);
            if (islet > 1.0 - a.islets) {
                double lift = (islet - (1.0 - a.islets)) / Math.max(0.05, a.islets);
                bed = Math.max(bed, is.waterLevel + 1.0 + 6.0 * lift - 2.0);
            }
            double into = smoothstep(0.0, a.bowlSteepness, inside);
            top += (bed - top) * into;
            if (inside > -0.02 && top < is.waterLevel) level = is.waterLevel;
        }
        if (is.terrace > 0) top = ground + Math.floor((top - ground) / is.terrace) * is.terrace;
        // A slight drop at the very edge makes a crisp cliff lip.
        top -= 1.5 * (1.0 - smoothstep(0.0, 0.04, t));
        // Underside: a cliff wall at the edge, then an inverted mountain, lumpy at two scales, with drips.
        double cone = Math.pow(smoothstep(0.0, 1.0, Math.min(1.0, 1.1 * t)), 0.65);
        double depth = is.edge + is.under * is.r * cone;
        depth *= 1.0 + a.undersideNoise * (sn(x, z, 0.5 * is.r + 8.0, is.ox + 4049.0, is.oz + 1123.0)
                + 0.5 * sn(x, z, 0.2 * is.r + 6.0, is.oz - 3301.0, is.ox + 887.0));
        double drip = sn(x, z, 22.0, is.ox - 777.0, is.oz + 555.0);
        if (drip > 0.15) depth += a.drips * Math.sqrt(is.r) * 3.0 * (drip - 0.15) * smoothstep(0.0, 0.25, t);
        // The higher it rises, the deeper it hangs: mountain islands grow roots, and peaked islets become spikes.
        if (is.peak > 0) depth += a.peakRoot * is.peak * Math.pow(smoothstep(0.0, 1.0, t), 1.4);
        double bottom = Math.min(ground - depth, top - 4.0);
        int i = c.count++;
        c.top[i] = B * top;
        c.bottom[i] = B * bottom;
        c.k[i] = is.wild > 0 ? is.wild * smoothstep(0.0, 0.3, t) : 0.0;
        c.maxK = Math.max(c.maxK, c.k[i]);
        c.mr[i] = t;
        c.yc[i] = is.base;
        c.basinLevel[i] = level;
    }

    // ---- Basins: calderas and atolls ----------------------------------------------------------------------------

    /**
     * A round island built around water. A caldera is a crater lake inside a raised rim; an atoll is a low ring of land
     * around a shallow lagoon, with optional islets. Its water level is fixed once per basin.
     */
    private record Basin(int x, int z, double radius, double level, boolean atoll, int layer) {}

    private Basin basin(int cellX, int cellZ) {
        long key = mix((cellX & 0x3FFFFFFL) | (cellZ & 0x3FFFFFFL) << 26);
        return basins.computeIfAbsent(key, k -> buildBasin(cellX, cellZ));
    }

    private Basin buildBasin(int cellX, int cellZ) {
        long h = mix(seedMix ^ cellX * 0x632BE59BD9B4E019L ^ cellZ * 0x85157AF5L ^ 0x2545F4914F6CDD1DL);
        if (unit(h) >= p.basinChance) return NO_BASIN;
        double size = unit(h = mix(h));
        double radius = p.basinMinRadius + (p.basinMaxRadius - p.basinMinRadius) * size * size;
        boolean atoll = unit(h = mix(h)) < p.basinAtollShare;
        int layer = Math.min(p.layers - 1, (int) (unit(h = mix(h)) * p.layers));
        int cx = cellX * p.basinCell + (int) (p.basinCell * (0.25 + 0.5 * unit(h = mix(h))));
        int cz = cellZ * p.basinCell + (int) (p.basinCell * (0.25 + 0.5 * unit(h = mix(h))));
        Column c = new Column(this, cx, cz);
        fill(c, false, false);
        if (c.wild > 0.0) return NO_BASIN;
        return new Basin(cx, cz, radius, Math.floor(c.yc[layer]), atoll, layer);
    }

    /** Distance from the basin's centre scaled so 1.0 is the shoreline, with a wobble so it isn't a circle. */
    private double basinDistance(Basin b, int x, int z) {
        double dx = x - b.x, dz = z - b.z;
        double r = Math.sqrt(dx * dx + dz * dz) / b.radius;
        return r + p.basinWobble * surf(x * 0.35 + 2711.0, z * 0.35 - 1597.0);
    }

    private double basinOuter(Basin b) {
        return 1.0 + (b.atoll ? p.basinRingWidth : p.basinRimWidth);
    }

    /** Reshapes one layer's top and bottom (tb, in blocks) for any basin here. Returns its water level, or NaN. */
    private double applyBasin(int x, int z, int layer, double[] tb) {
        int cellX = Math.floorDiv(x, p.basinCell), cellZ = Math.floorDiv(z, p.basinCell);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Basin b = basin(cellX + dx, cellZ + dz);
                if (b == NO_BASIN || b.layer != layer) continue;
                double outer = basinOuter(b);
                double r = basinDistance(b, x, z);
                if (r >= outer) continue;
                double L = b.level, newTop;
                double depth = b.atoll ? p.basinLagoonDepth : p.basinCalderaDepth;
                if (r < 1.0) {
                    newTop = b.atoll ? L - 1.0 - depth * (1.0 - r * r * r * r) : L - 1.0 - depth * (1.0 - r * r);
                    if (b.atoll && p.basinIslets > 0) {
                        double n = surf(x * 0.9 + 613.0, z * 0.9 - 1931.0);
                        double t = 1.0 - p.basinIslets;
                        if (n > t) newTop = Math.max(newTop, L + 1.0 + 5.0 * (n - t) / p.basinIslets);
                    }
                } else if (b.atoll) {
                    double t = (r - 1.0) / p.basinRingWidth;
                    newTop = L + 1.0 + p.basinRingHeight * (1.0 - (2.0 * t - 1.0) * (2.0 * t - 1.0));
                } else {
                    double t = (r - 1.0) / p.basinRimWidth;
                    double rise = smoothstep(0.0, 0.35, t), fall = smoothstep(0.35, 1.0, t);
                    newTop = L + 1.0 + p.basinRimHeight * (rise - 0.75 * fall);
                }
                double m = clamp(1.0 - r / outer, 0.0, 1.0);
                double newBottom = Math.min(newTop - 4.0, L - depth - p.basinThickness * clamp(4.0 * m, 0.0, 1.0) - p.basinUnderside * Math.sqrt(m));
                boolean land = tb[0] > tb[1] + 1.0;
                if (r < 1.0) {
                    tb[0] = newTop;
                    tb[1] = land ? Math.min(tb[1], newBottom) : newBottom;
                    return L;
                }
                tb[0] = land ? Math.max(tb[0], newTop) : newTop;
                tb[1] = land ? Math.min(tb[1], newBottom) : newBottom;
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    /** For tools: basins whose centres fall in the area, each as {x, z, radius, water level, 1 if atoll else 0, layer}. */
    public java.util.List<double[]> basinsIn(int x0, int z0, int x1, int z1) {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        if (p.basinCell <= 0 || basins == null) return out;
        for (int cx = Math.floorDiv(x0, p.basinCell); cx <= Math.floorDiv(x1, p.basinCell); cx++) {
            for (int cz = Math.floorDiv(z0, p.basinCell); cz <= Math.floorDiv(z1, p.basinCell); cz++) {
                Basin b = basin(cx, cz);
                if (b != NO_BASIN && b.x >= x0 && b.x <= x1 && b.z >= z0 && b.z <= z1) {
                    out.add(new double[]{b.x, b.z, b.radius, b.level, b.atoll ? 1 : 0, b.layer});
                }
            }
        }
        return out;
    }

    /**
     * For tools: archipelago main islands whose centres fall in the area, each as
     * {x, z, radius, base height, kind} with kind 0 plain, 1 lake, 2 peak, 3 stepped.
     */
    public java.util.List<double[]> islandsIn(int x0, int z0, int x1, int z1) {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        if (p.arch == null || islands == null) return out;
        int c = (int) p.arch.cell;
        for (int cx = Math.floorDiv(x0, c) - 1; cx <= Math.floorDiv(x1, c) + 1; cx++) {
            for (int cz = Math.floorDiv(z0, c) - 1; cz <= Math.floorDiv(z1, c) + 1; cz++) {
                Island is = mainIsland(cx, cz);
                if (is == NO_ISLAND || is.x < x0 || is.x > x1 || is.z < z0 || is.z > z1) continue;
                int kind = is.bowlDepth > 0 ? 1 : is.peak > 0 ? 2 : is.terrace > 0 ? 3 : 0;
                out.add(new double[]{is.x, is.z, is.r, is.base, kind});
            }
        }
        return out;
    }

    /**
     * For tools: the solid spans at (x, z) as {top, bottom, water level or NaN} in blocks, straight from the shape maths
     * (before the game's interpolation), so a map of a large area costs microseconds per column instead of chunk generation.
     */
    public double[] spansAt(int x, int z) {
        Column c = new Column(this, x, z);
        fill(c, true, true);
        double[] out = new double[3 * c.count];
        for (int i = 0; i < c.count; i++) {
            out[3 * i] = c.top[i] / B;
            out[3 * i + 1] = c.bottom[i] / B;
            out[3 * i + 2] = c.basinLevel[i];
        }
        return out;
    }

    /** For tools: how strongly this column is in a plateau region, 0 to 1. */
    public double plateauWeight(int x, int z) {
        if (p.plateauScale <= 0) return 0.0;
        Column c = new Column(this, x, z);
        fill(c, false, false);
        return c.plateau;
    }

    /** True if a basin's land reaches this column on any layer (lakes keep out of basins). */
    private boolean inBasin(int x, int z) {
        if (p.basinCell <= 0) return false;
        int cellX = Math.floorDiv(x, p.basinCell), cellZ = Math.floorDiv(z, p.basinCell);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Basin b = basin(cellX + dx, cellZ + dz);
                if (b != NO_BASIN && basinDistance(b, x, z) < basinOuter(b) + 0.3) return true;
            }
        }
        return false;
    }

    private static double smoothstep(double a, double b, double v) {
        double t = clamp((v - a) / (b - a), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    // ---- Lakes -------------------------------------------------------------------------------------------------

    private record Lake(int x, int z, double radius, double level, double depth) {}

    /** One candidate lake per grid cell per layer, accepted only where the land around it is level enough to hold water. */
    private Lake lake(int cellX, int cellZ, int layer) {
        long key = mix((cellX & 0x3FFFFFFL) | (cellZ & 0x3FFFFFFL) << 26 | (long) layer << 52);
        return lakes.computeIfAbsent(key, k -> buildLake(cellX, cellZ, layer));
    }

    private Lake buildLake(int cellX, int cellZ, int layer) {
        long h = mix(seedMix ^ cellX * 0x9E3779B97F4A7C15L ^ cellZ * 0xC2B2AE3D27D4EB4FL ^ layer * 0x165667B19E3779F9L);
        if (unit(h) >= p.lakeChance) return NO_LAKE;
        double size = unit(h = mix(h));
        double radius = p.lakeMinRadius + (p.lakeMaxRadius - p.lakeMinRadius) * size * size * size;
        // Most spots in a cell are no good, so try several before giving up on this cell.
        for (int attempt = 0; attempt < p.lakeAttempts; attempt++) {
            int cx = cellX * p.lakeCell + (int) (p.lakeCell * (0.25 + 0.5 * unit(h = mix(h))));
            int cz = cellZ * p.lakeCell + (int) (p.lakeCell * (0.25 + 0.5 * unit(h = mix(h))));
            Lake lake = tryLakeAt(cx, cz, radius, layer);
            if (lake != null) return lake;
        }
        return NO_LAKE;
    }

    /** A lake needs calm land that's inland, level around its rim, and thick enough that the bed stays inside the island. */
    private Lake tryLakeAt(int cx, int cz, double radius, int layer) {
        if (inBasin(cx, cz)) return null;
        Column c = new Column(this, cx, cz);
        fill(c, false, false);
        if (c.wild > 0.0 || c.mr[layer] < p.lakeCentreInland) return null;
        double sum = c.top[layer] / B, low = sum, high = sum, deepestBottom = c.bottom[layer] / B;
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI / 6.0;
            Column rim = new Column(this, (int) (cx + Math.cos(angle) * radius * 1.35), (int) (cz + Math.sin(angle) * radius * 1.35));
            fill(rim, false, false);
            if (rim.wild > 0.0 || rim.mr[layer] < p.lakeRimInland) return null;
            double t = rim.top[layer] / B;
            deepestBottom = Math.max(deepestBottom, rim.bottom[layer] / B);
            sum += t;
            low = Math.min(low, t);
            high = Math.max(high, t);
        }
        if (high - low > p.lakeRimTolerance) return null;
        double level = Math.floor(sum / 13.0);
        double depth = Math.min(p.lakeMaxDepth, 2.0 + radius * 0.08);
        if (level - 1.0 - depth - deepestBottom < 6.0) return null;
        return new Lake(cx, cz, radius, level, depth);
    }

    /** Lake distance scaled so 1.0 is the shoreline, with a noise wobble so shores aren't circles. NaN means too far away. */
    private double lakeDistance(Lake lake, int x, int z, int layer) {
        double dx = x - lake.x, dz = z - lake.z;
        double limit = lake.radius * (1.0 + p.lakeShore + p.lakeWobble);
        double d2 = dx * dx + dz * dz;
        if (d2 >= limit * limit) return Double.NaN;
        return Math.sqrt(d2) / lake.radius + p.lakeWobble * surf(x * 0.7 + 1777.0 + 233.0 * layer, z * 0.7 - 911.0 - 71.0 * layer);
    }

    private double shapeAroundLake(int x, int z, int layer, double top) {
        int cellX = Math.floorDiv(x, p.lakeCell), cellZ = Math.floorDiv(z, p.lakeCell);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Lake lake = lake(cellX + dx, cellZ + dz, layer);
                if (lake == NO_LAKE) continue;
                double r = lakeDistance(lake, x, z, layer);
                if (Double.isNaN(r) || r >= 1.0 + p.lakeShore) continue;
                if (r < 1.0) return lake.level - 1.0 - lake.depth * (1.0 - r * r);
                double t = (r - 1.0) / p.lakeShore;
                double blend = t * t * (3.0 - 2.0 * t);
                return (lake.level + 1.0) * (1.0 - blend) + top * blend;
            }
        }
        return top;
    }

    private int waterLevel(int x, int z, int layer) {
        if (p.basinCell > 0) {
            int bx = Math.floorDiv(x, p.basinCell), bz = Math.floorDiv(z, p.basinCell);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Basin b = basin(bx + dx, bz + dz);
                    if (b != NO_BASIN && b.layer == layer && basinDistance(b, x, z) < 1.0) return (int) b.level;
                }
            }
        }
        if (p.lakeCell <= 0) return Integer.MIN_VALUE;
        int cellX = Math.floorDiv(x, p.lakeCell), cellZ = Math.floorDiv(z, p.lakeCell);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Lake lake = lake(cellX + dx, cellZ + dz, layer);
                if (lake == NO_LAKE) continue;
                double r = lakeDistance(lake, x, z, layer);
                if (!Double.isNaN(r) && r < 1.0) return (int) lake.level;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** A picker for one chunk. Vanilla asks once per air block in x/z order, so answers are kept per column of the chunk. */
    public Aquifer.FluidPicker fluidPicker(Aquifer.FluidPicker fallback) {
        if (p.arch != null) return new ArchPicker(fallback);
        if (p.lakeCell <= 0 && p.basinCell <= 0) return fallback;
        final int layers = p.layers;
        final int[] levels = new int[256 * layers];
        java.util.Arrays.fill(levels, UNKNOWN);
        return (x, y, z) -> {
            int column = ((x & 15) << 4 | z & 15) * layers;
            if (levels[column] == UNKNOWN) {
                for (int l = 0; l < layers; l++) levels[column + l] = waterLevel(x, z, l);
            }
            for (int l = 0; l < layers; l++) {
                int level = levels[column + l];
                // The lower bound stops water filling the open sky underneath the island.
                if (level != Integer.MIN_VALUE && y < level && y >= level - p.maxWaterDepth - 4) return water(level);
            }
            return fallback.computeFluid(x, y, z);
        };
    }

    /**
     * Archipelago lakes for one chunk: water from each lake island's level down to its lake bed, only in its lake
     * columns. Vanilla asks once per air block, so each column's answer is kept. Most chunks have no lake island
     * anywhere near, and those are found once per chunk instead of filling all 256 columns.
     */
    public final class ArchPicker implements Aquifer.FluidPicker {
        private static final int CAP = 8;
        private final Aquifer.FluidPicker fallback;
        private final double[] levels = new double[256 * CAP], floors = new double[256 * CAP];
        private final int[] counts = new int[256];
        /** 0 = not worked out yet, 1 = no lake island reaches this chunk, 2 = one might. */
        private int lakes;

        ArchPicker(Aquifer.FluidPicker fallback) {
            this.fallback = fallback;
            java.util.Arrays.fill(counts, -1);
        }

        @Override
        public Aquifer.FluidStatus computeFluid(int x, int y, int z) {
            if (lakes(x, z)) {
                int column = column(x, z);
                for (int i = 0; i < counts[column]; i++) {
                    double level = levels[column * CAP + i];
                    if (!Double.isNaN(level) && y < level && y > floors[column * CAP + i]) return water((int) level);
                }
            }
            return fallback.computeFluid(x, y, z);
        }

        /** True if any block of the box x0..x0+w-1, y0..y1, z0..z0+w-1 (inside this chunk) could be lake water. */
        public boolean mayHaveWater(int x0, int z0, int w, int y0, int y1) {
            if (!lakes(x0, z0)) return false;
            for (int x = x0; x < x0 + w; x++) {
                for (int z = z0; z < z0 + w; z++) {
                    int column = column(x, z);
                    for (int i = 0; i < counts[column]; i++) {
                        double level = levels[column * CAP + i];
                        if (!Double.isNaN(level) && y0 < level && y1 > floors[column * CAP + i]) return true;
                    }
                }
            }
            return false;
        }

        private boolean lakes(int x, int z) {
            if (lakes == 0) lakes = !FastGen.enabled() || chunkHasLake(x & ~15, z & ~15) ? 2 : 1;
            return lakes == 2;
        }

        private int column(int x, int z) {
            int column = (x & 15) << 4 | z & 15;
            if (counts[column] < 0) {
                Column c = new Column(SkyTerrain.this, x, z);
                fill(c, true, true);
                for (int i = 0; i < c.count; i++) {
                    levels[column * CAP + i] = c.basinLevel[i];
                    // Only down to the lake bed (a little below, for interpolation), not to the underside:
                    // caves and overhangs under a lake must stay dry.
                    floors[column * CAP + i] = c.top[i] / B - 3.0;
                }
                counts[column] = c.count;
            }
            return column;
        }
    }

    /**
     * Whether any island that has a lake (only those set a column's basinLevel) can reach the 16x16 chunk at x0, z0.
     * It looks at the same candidates fillArchipelago does for each column, with the same reach test as the pepper
     * tiles, so it never says no where a column would find a lake.
     */
    private boolean chunkHasLake(int x0, int z0) {
        Arch a = p.arch;
        int x1 = x0 + 15, z1 = z0 + 15;
        // The same cell arithmetic as addMainIslands and fillArchipelago, over the chunk's first and last column.
        for (int cx = (int) Math.floor(x0 / a.cell) - a.search; cx <= (int) Math.floor(x1 / a.cell) + a.search; cx++)
            for (int cz = (int) Math.floor(z0 / a.cell) - a.search; cz <= (int) Math.floor(z1 / a.cell) + a.search; cz++)
                if (lakeReaches(mainIsland(cx, cz), x0, z0, x1, z1)) return true;
        if (a.leySpacing > 0) {
            for (int cx = (int) Math.floor(x0 / a.leyCell) - a.leySearch; cx <= (int) Math.floor(x1 / a.leyCell) + a.leySearch; cx++)
                for (int cz = (int) Math.floor(z0 / a.leyCell) - a.leySearch; cz <= (int) Math.floor(z1 / a.leyCell) + a.leySearch; cz++)
                    if (lakeReaches(leyIsland(cx, cz), x0, z0, x1, z1)) return true;
        }
        if (a.pepperChance > 0) {
            for (Island is : pepperTile(Math.floorDiv(x0, PEPPER_TILE), Math.floorDiv(z0, PEPPER_TILE)))
                if (lakeReaches(is, x0, z0, x1, z1)) return true;
        }
        return false;
    }

    private boolean lakeReaches(Island is, int x0, int z0, int x1, int z1) {
        if (is == NO_ISLAND || !(is.bowlDepth > 0)) return false;
        double dx = Math.max(0.0, Math.max(x0 - is.x, is.x - x1)), dz = Math.max(0.0, Math.max(z0 - is.z, is.z - z1));
        // Twice the shared warp's room: sharedRoom() rests on the noise's sampled maximum, and a missed lake would leave
        // a dry hole, so this test leaves extra margin. A false yes only costs the per-column check.
        double room = is.reach + 2.0 * p.arch.sharedRoom();
        return dx * dx + dz * dz < room * room;
    }

    private static Aquifer.FluidStatus water(int level) {
        int i = level + 1024;
        Aquifer.FluidStatus s = WATER[i];
        if (s == null) WATER[i] = s = new Aquifer.FluidStatus(level, Blocks.WATER.defaultBlockState());
        return s;
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------

    private double surf(double x, double z) { return surface.getValue(x, 0.0, z); }
    private double veg(double x, double z) { return vegetation.getValue(x, 0.0, z); }
    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }
    private static double unit(long h) { return (h >>> 11) * 0x1.0p-53; }
    private static long mix(long z) {
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        return z ^ z >>> 31;
    }

    @Override public void fillArray(double[] out, ContextProvider ctx) { ctx.fillAllDirectly(out, this); }
    @Override public double minValue() { return -128.0; }
    @Override public double maxValue() { return 128.0; }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }

    private static final class Column {
        final SkyTerrain owner;
        final int x, z;
        final double[] top, bottom, k, mr, yc, basinLevel;
        final double[] scratch = new double[2];
        double maxK, wild, plateau, channel = Double.NaN;
        /** sharedWarp(x, z), set when an archipelago column is filled. */
        double[] sharedWarp;
        /** Solid spans in use: the layer count in layered mode, the islands reaching this column in archipelago mode. */
        int count;

        Column(SkyTerrain owner, int x, int z) {
            int layers = owner.p.arch != null ? 8 : owner.p.layers;
            this.owner = owner;
            this.x = x;
            this.z = z;
            top = new double[layers];
            bottom = new double[layers];
            k = new double[layers];
            mr = new double[layers];
            yc = new double[layers];
            basinLevel = new double[layers];
        }
    }

    private static final class Params {
        final JsonElement json;
        final int layers;
        final double[] weights = new double[4], bandScale = new double[4], layerOffset;
        final double cutoff, openSky, fillHoles, coastWidth, taper, dome, underside, gap, maxGap, heightSpread, driftScale, smallScatter, merging, gapScale;
        final double wildScale, wildShiftX, wildShiftZ, wildThreshold, wildFade, strength, interiorCap, thicken, hills;
        final int lakeCell;
        final double lakeChance, lakeMinRadius, lakeMaxRadius, lakeShore, lakeWobble, lakeRimTolerance, lakeCentreInland, lakeRimInland;
        final int lakeMaxDepth, lakeAttempts;
        // Plateau regions. Off unless the settings have a "plateau" object.
        final double plateauScale, plateauShiftX, plateauShiftZ, plateauThreshold, plateauFade, plateauGrow, plateauSmooth;
        final double plateauHeight, plateauVariety, plateauCliff, plateauFlat, plateauLevel, plateauTerrace, plateauUnderside;
        // Calderas and atolls. Off unless the settings have a "basins" object.
        final int basinCell;
        final double basinChance, basinMinRadius, basinMaxRadius, basinAtollShare, basinWobble, basinRimWidth, basinRimHeight;
        final double basinCalderaDepth, basinRingWidth, basinRingHeight, basinLagoonDepth, basinIslets, basinThickness, basinUnderside;
        final double maxWaterDepth;
        /** Archipelago mode, or null for the layered mode. */
        final Arch arch;

        private static double num(JsonObject o, String key, double fallback) {
            return o != null && o.has(key) ? o.get(key).getAsDouble() : fallback;
        }

        Params(JsonElement json) {
            this.json = json;
            JsonObject s = json.getAsJsonObject();
            double l0 = s.get("L0").getAsDouble();
            JsonArray w = s.getAsJsonArray("weights");
            for (int i = 0; i < 4; i++) {
                weights[i] = w.get(i).getAsDouble();
                bandScale[i] = 64.0 / (l0 / Math.pow(4.0, i));
            }
            cutoff = s.get("cutoff").getAsDouble();
            openSky = s.get("openSky").getAsDouble();
            fillHoles = s.get("fillHoles").getAsDouble();
            coastWidth = s.get("coastWidth").getAsDouble();
            taper = s.get("taper").getAsDouble();
            dome = s.get("dome").getAsDouble();
            underside = s.get("underside").getAsDouble();
            layers = s.get("layers").getAsInt();
            gap = s.get("layerGap").getAsDouble();
            maxGap = layers == 3 ? 125.0 : 200.0;
            layerOffset = layers == 1 ? new double[]{0.0} : layers == 2 ? new double[]{0.5, -0.5} : new double[]{1.0, 0.0, -1.0};
            heightSpread = s.get("heightSpread").getAsDouble();
            driftScale = 256.0 / s.get("heightDriftWidth").getAsDouble();
            smallScatter = s.get("smallScatter").getAsDouble();
            merging = s.get("layerMerging").getAsDouble();
            gapScale = 256.0 / s.get("mergingWidth").getAsDouble();
            JsonObject wild = s.getAsJsonObject("wild");
            wildScale = 256.0 / wild.get("widthBlocks").getAsDouble();
            wildShiftX = wild.get("shiftX").getAsDouble();
            wildShiftZ = wild.get("shiftZ").getAsDouble();
            wildThreshold = wild.get("threshold").getAsDouble();
            wildFade = wild.get("fade").getAsDouble();
            strength = wild.get("strength").getAsDouble();
            interiorCap = wild.get("interiorCap").getAsDouble();
            thicken = wild.get("thicken").getAsDouble();
            hills = wild.get("hills").getAsDouble();
            JsonObject lakes = s.has("lakes") ? s.getAsJsonObject("lakes") : null;
            lakeCell = lakes == null ? 0 : lakes.get("cell").getAsInt();
            lakeChance = lakes == null ? 0 : lakes.get("chance").getAsDouble();
            lakeMinRadius = lakes == null ? 0 : lakes.get("minRadius").getAsDouble();
            lakeMaxRadius = lakes == null ? 0 : lakes.get("maxRadius").getAsDouble();
            lakeShore = lakes == null ? 0 : lakes.get("shore").getAsDouble();
            lakeWobble = lakes == null ? 0 : lakes.get("wobble").getAsDouble();
            lakeRimTolerance = lakes == null ? 0 : lakes.get("rimTolerance").getAsDouble();
            lakeMaxDepth = lakes == null ? 0 : lakes.get("maxDepth").getAsInt();
            lakeAttempts = lakes == null ? 0 : lakes.get("attempts").getAsInt();
            lakeCentreInland = lakes == null ? 0 : lakes.get("centreInland").getAsDouble();
            lakeRimInland = lakes == null ? 0 : lakes.get("rimInland").getAsDouble();

            JsonObject pl = s.has("plateau") ? s.getAsJsonObject("plateau") : null;
            plateauScale = pl == null ? 0 : 256.0 / num(pl, "widthBlocks", 3000);
            plateauShiftX = num(pl, "shiftX", -1777.0);
            plateauShiftZ = num(pl, "shiftZ", 2903.0);
            plateauThreshold = num(pl, "threshold", 0.2);
            plateauFade = num(pl, "fade", 0.15);
            plateauGrow = num(pl, "grow", 0.25);
            plateauSmooth = num(pl, "smooth", 0.7);
            plateauHeight = num(pl, "height", 30);
            plateauVariety = num(pl, "variety", 0.4);
            plateauCliff = num(pl, "cliff", 6.0);
            plateauFlat = num(pl, "flat", 0.9);
            plateauLevel = num(pl, "level", 0.6);
            plateauTerrace = num(pl, "terrace", 0);
            plateauUnderside = num(pl, "underside", 30);

            JsonObject bs = s.has("basins") ? s.getAsJsonObject("basins") : null;
            basinCell = bs == null ? 0 : (int) num(bs, "cell", 1100);
            basinChance = num(bs, "chance", 0.5);
            basinMinRadius = num(bs, "minRadius", 60);
            basinMaxRadius = num(bs, "maxRadius", 240);
            basinAtollShare = num(bs, "atollShare", 0.5);
            basinWobble = num(bs, "wobble", 0.12);
            basinRimWidth = num(bs, "rimWidth", 0.45);
            basinRimHeight = num(bs, "rimHeight", 22);
            basinCalderaDepth = num(bs, "calderaDepth", 18);
            basinRingWidth = num(bs, "ringWidth", 0.25);
            basinRingHeight = num(bs, "ringHeight", 3);
            basinLagoonDepth = num(bs, "lagoonDepth", 6);
            basinIslets = num(bs, "islets", 0.25);
            basinThickness = num(bs, "thickness", 10);
            basinUnderside = num(bs, "underside", 50);
            maxWaterDepth = Math.max(lakeMaxDepth, basinCell > 0 ? Math.max(basinCalderaDepth, basinLagoonDepth) + 1 : 0);
            arch = s.has("archipelago") ? new Arch(s.getAsJsonObject("archipelago")) : null;
        }
    }

    /** Archipelago settings: islands placed on jittered grids, each with its own rolled character. */
    private static final class Arch {
        final double cell, chance, jitter, minRadius, maxRadius, sizeSkew, elongation, coastWarp, regionScale;
        final double baseY, regionDrift, heightSpread, dome, hills;
        final double bowlChance, bowlMinRadius, bowlDepthMin, bowlDepthMax, bowlWidthMin, bowlWidthMax, bowlSteepness;
        final double rimMax, waterline, shoreWarp, islets, terraceChance, terraceStep, peakChance, peakMin, peakMax;
        final double underside, undersideNoise, drips, edgeThickness, lobes, domainWarp;
        final double pepperCluster, flowAlign, sharedWarp, sharedWarpWidth;
        final double channelWidth, channelWidthVar, channelStrength, channelPepper, openSky, channelCoreResist;
        final double cliffRim, hybridPeak, wildChance, wildStrength, tierChance, tierMax, peakRoot;
        final double leyScale, leySpacing, leyPull, leyPullPepper, leyBand, leyPepperBand, leyOff, leyAlign, leyStretch, lineHeightSpread, leyGaps;
        final double leyCell, leyChance, leyMinRadius, leyMaxRadius;
        final int leySearch, pepperSearch, coastSearch, leyCoastSearch;
        final double pepperCell, pepperChance, pepperMinRadius, pepperMaxRadius, pepperHeightSpread, pepperUnderside;
        final int search;

        Arch(JsonObject o) {
            cell = Params.num(o, "cell", 380);
            chance = Params.num(o, "chance", 0.8);
            jitter = Params.num(o, "jitter", 0.7);
            minRadius = Params.num(o, "minRadius", 35);
            maxRadius = Params.num(o, "maxRadius", 190);
            sizeSkew = Params.num(o, "sizeSkew", 1.8);
            elongation = Params.num(o, "elongation", 0.6);
            coastWarp = Params.num(o, "coastWarp", 0.3);
            regionScale = 256.0 / Params.num(o, "regionWidth", 2400);
            baseY = Params.num(o, "baseY", 170);
            regionDrift = Params.num(o, "regionDrift", 35);
            heightSpread = Params.num(o, "heightSpread", 22);
            dome = Params.num(o, "dome", 10);
            hills = Params.num(o, "hills", 8);
            bowlChance = Params.num(o, "bowlChance", 0.45);
            bowlMinRadius = Params.num(o, "bowlMinRadius", 50);
            bowlDepthMin = Params.num(o, "bowlDepthMin", 8);
            bowlDepthMax = Params.num(o, "bowlDepthMax", 28);
            bowlWidthMin = Params.num(o, "bowlWidthMin", 0.35);
            bowlWidthMax = Params.num(o, "bowlWidthMax", 0.85);
            bowlSteepness = Params.num(o, "bowlSteepness", 0.18);
            rimMax = Params.num(o, "rimMax", 14);
            waterline = Params.num(o, "waterline", 4);
            shoreWarp = Params.num(o, "shoreWarp", 0.18);
            islets = Params.num(o, "islets", 0.3);
            terraceChance = Params.num(o, "terraceChance", 0.15);
            terraceStep = Params.num(o, "terraceStep", 7);
            peakChance = Params.num(o, "peakChance", 0.12);
            peakMin = Params.num(o, "peakMin", 30);
            peakMax = Params.num(o, "peakMax", 100);
            underside = Params.num(o, "underside", 0.7);
            undersideNoise = Params.num(o, "undersideNoise", 0.35);
            drips = Params.num(o, "drips", 1.0);
            edgeThickness = Params.num(o, "edgeThickness", 12);
            lobes = Params.num(o, "lobes", 3);
            domainWarp = Params.num(o, "domainWarp", 0.25);
            JsonObject pp = o.has("pepper") ? o.getAsJsonObject("pepper") : null;
            pepperCell = Params.num(pp, "cell", 60);
            pepperChance = pp == null ? 0 : Params.num(pp, "chance", 0.3);
            pepperMinRadius = Params.num(pp, "minRadius", 4);
            pepperMaxRadius = Params.num(pp, "maxRadius", 14);
            pepperHeightSpread = Params.num(pp, "heightSpread", 70);
            pepperUnderside = Params.num(pp, "underside", 1.6);
            pepperCluster = Params.num(pp, "cluster", 0.8);
            flowAlign = Params.num(o, "flowAlign", 0);
            sharedWarp = Params.num(o, "sharedWarp", 0);
            sharedWarpWidth = Params.num(o, "sharedWarpWidth", 500);
            JsonObject ch = o.has("channels") ? o.getAsJsonObject("channels") : null;
            channelWidth = ch == null ? 0 : Params.num(ch, "width", 40);
            channelWidthVar = Params.num(ch, "widthVariation", 0.5);
            channelStrength = Params.num(ch, "strength", 1.2);
            channelPepper = Params.num(ch, "islets", 0.6);
            channelCoreResist = Params.num(ch, "coreResist", 0.8);
            openSky = Params.num(o, "openSky", 0);
            cliffRim = Params.num(o, "cliffRim", 0);
            hybridPeak = Params.num(o, "hybridPeak", 0);
            wildChance = Params.num(o, "wildChance", 0);
            wildStrength = Params.num(o, "wildStrength", 1.0);
            tierChance = Params.num(o, "tierChance", 0);
            tierMax = Params.num(o, "tierMax", 20);
            peakRoot = Params.num(o, "peakRoot", 1.0);
            JsonObject ley = o.has("ley") ? o.getAsJsonObject("ley") : null;
            leyScale = 256.0 / Params.num(ley, "width", 2600);
            leySpacing = ley == null ? 0 : Params.num(ley, "spacing", 0.2);
            leyPull = Params.num(ley, "pull", 0.75);
            leyPullPepper = Params.num(ley, "pullPepper", 0.5);
            leyBand = Params.num(ley, "band", 90);
            leyPepperBand = Params.num(ley, "pepperBand", 150);
            leyOff = Params.num(ley, "off", 0.12);
            leyAlign = Params.num(ley, "align", 0.8);
            leyStretch = Params.num(ley, "stretch", 0.6);
            lineHeightSpread = Params.num(ley, "lineHeightSpread", 45);
            leyGaps = Params.num(ley, "gaps", 0.5);
            leyCell = Params.num(ley, "cell", 170);
            leyChance = Params.num(ley, "chance", 1.0);
            leyMinRadius = Params.num(ley, "minRadius", 18);
            leyMaxRadius = Params.num(ley, "maxRadius", 80);
            // How many cells away an island's land can reach, so each column asks every cell that might cover it: the
            // biggest island's reach (lobes reach 1.6 r out, main islands grow up to 1.25x in big regions), plus the
            // shared warp. The ley pull moves ley islands and islets up to one of their own cells, so those add a cell.
            double mainShape = 1.6 * Math.sqrt(1.0 + elongation);
            search = (int) Math.ceil((reach(maxRadius * 1.25, mainShape, 1.0) + sharedRoom()) / cell);
            double pullRoom = leySpacing > 0 ? 1.0 : 0.0;
            double leyShape = 1.6 * Math.sqrt((1.0 + elongation) * (1.0 + leyStretch));
            leySearch = (int) Math.ceil((reach(leyMaxRadius, leyShape, 1.0) + sharedRoom()) / leyCell + pullRoom);
            pepperSearch = (int) Math.ceil((reach(pepperMaxRadius, mainShape, 1.0) + sharedRoom()) / pepperCell + pullRoom);
            // The islet coast query looks further: as far as the clustering band reaches.
            coastSearch = (int) Math.ceil((reach(maxRadius * 1.25, mainShape, coastBand()) + sharedRoom()) / cell);
            leyCoastSearch = (int) Math.ceil((reach(leyMaxRadius, leyShape, coastBand()) + sharedRoom()) / leyCell + pullRoom);
        }

        /** The largest |value| the warp noises reach in practice (about 1.49 over millions of samples; in theory 5). */
        static final double NOISE_MAX = 1.5;

        /**
         * How far from an island's centre, measured after the shared warp, shapeRn can still be under rn (land is under
         * 1): the lobe distance plus the coast roughening (up to 1.75 noises) has to be under rn, after the domain warp
         * (two noises) has moved the point. shape is the farthest lobe edge times the square root of the aspect ratio.
         */
        double reach(double r, double shape, double rn) {
            return r * (shape * (rn + 1.75 * NOISE_MAX * coastWarp) + Math.sqrt(2.0) * NOISE_MAX * domainWarp) + 1.0;
        }

        /** Islets cluster where the coast query is under this; past it they are as rare as in open sky. */
        double coastBand() {
            return 1.0 + pepperCluster;
        }

        /** The farthest the shared warp can move a point. */
        double sharedRoom() {
            return Math.sqrt(2.0) * NOISE_MAX * sharedWarp;
        }
    }
}
