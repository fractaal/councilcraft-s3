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
        final Map<Long, River> rivers = new ConcurrentHashMap<>();
        final Map<Long, River[]> riverTiles = new ConcurrentHashMap<>();
        final Map<Long, LandBridge> landBridges = new ConcurrentHashMap<>();
        final Map<Long, LandBridge[]> landBridgeTiles = new ConcurrentHashMap<>();
        final Map<Long, Island[]> bridgePicks = new ConcurrentHashMap<>();
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
        c.lakeNear = 0.0;
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
        if (!c.bare && a.landBridgeChance > 0) {
            for (LandBridge lb : landBridgeTile(Math.floorDiv(c.x, RTILE), Math.floorDiv(c.z, RTILE))) addLandBridge(c, lb);
        }
        if (!c.bare && a.riverChance > 0) {
            River[] rivers = riverTile(Math.floorDiv(c.x, RTILE), Math.floorDiv(c.z, RTILE));
            if (rivers.length > 0) {
                double[] at = c.scratch;
                for (River rv : rivers) if (rv.bridged() && locate(rv, c.x, c.z, at)) addBridge(c, rv, at[0], at[1]);
                for (River rv : rivers) if (locate(rv, c.x, c.z, at)) carveColumn(c, rv, at[0], at[1]);
            }
        }
        // Crags (3D detail noise) keep out of lakes and their banks, on every island here: a crag hollow in an island
        // that overlaps a lake island would fill with the lake's water and leave it standing against air.
        if (c.lakeNear > 0.0) {
            c.maxK = 0.0;
            for (int i = 0; i < c.count; i++) c.maxK = Math.max(c.maxK, c.k[i] *= 1.0 - c.lakeNear);
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
        return c.channel = channelAt(x, z);
    }

    private double channelAt(double x, double z) {
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
        return v;
    }

    /** The island's shape value at (x, z) with channels applied, as addIsland works it out: under 1 is land. */
    private double islandRn(Island is, double x, double z, double channelHere) {
        Arch a = p.arch;
        double rn = shapeMaths(is, x, z, sharedWarp(x, z), null);
        if (a.channelWidth > 0) {
            boolean islet = is.r <= a.pepperMaxRadius && is.bowlDepth == 0 && is.edge <= 3.0;
            double heart = smoothstep(0.35, 0.8, 1.0 - rn) * smoothstep(40.0, 120.0, is.r);
            rn += (islet ? a.channelPepper : a.channelStrength) * (Double.isNaN(channelHere) ? channelAt(x, z) : channelHere) * (1.0 - a.channelCoreResist * heart);
        }
        return rn;
    }

    /**
     * Where (x, z) is relative to a lake island's lake: below 0 is the lake area (the dip under the water level and
     * its shore), and the shore is at 0. t is how far in from the island's edge (1 - rn).
     */
    private double lakeInside(Island is, double x, double z, double t) {
        return t - (1.0 - is.bowlWidth) + p.arch.shoreWarp * sn(x, z, 0.4 * is.r + 8.0, is.ox - 919.0, is.oz + 457.0);
    }

    /**
     * How many blocks (x, z) is outside the lake's water area, negative inside it. The water area is the lake area
     * minus a strip lakeCoastGap blocks wide along the island's edge. Distances come from how fast the lake and shape
     * values change here (value / slope), measured over 2 blocks, because coast noise, shore noise and channels can
     * make them change several times faster than 1 / radius.
     */
    private double lakeOutside(Island is, double x, double z, double rn, double inside) {
        double e = 2.0;
        double rx = islandRn(is, x + e, z, Double.NaN), rz = islandRn(is, x, z + e, Double.NaN);
        double ix = lakeInside(is, x + e, z, 1.0 - rx), iz = lakeInside(is, x, z + e, 1.0 - rz);
        double gi = Math.hypot(ix - inside, iz - inside) / e, gt = Math.hypot(rx - rn, rz - rn) / e;
        double outsideLake = (-0.02 - inside) / Math.max(gi, 1e-9);
        double toEdge = (1.0 - rn) / Math.max(gt, 1e-9);
        return Math.max(outsideLake, p.arch.lakeCoastGap - toEdge);
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
        double inside = is.bowlDepth > 0 ? lakeInside(is, x, z, t) : 0.0;
        top += is.hills * (sn(x, z, Math.max(32.0, 0.6 * is.r), is.ox + 711.0, is.oz - 333.0)
                + 0.45 * sn(x, z, Math.max(14.0, 0.22 * is.r), is.oz + 211.0, is.ox - 919.0)) * rise;
        // A lake island rises on one side: the raised side's land climbs to a plateau that ends in a cliff over the water,
        // and the island's mountains, if it has any, stand there. The rest of the shore stays low.
        double raised = is.bowlDepth > 0 ? raisedSide(is, x, z) : 0.0;
        if (is.peak > 0) {
            // Ridged noise: sharp crests where the noise crosses zero, so peaks come as ranges rather than cones.
            double ridge = 1.0 - Math.abs(sn(x, z, 0.45 * is.r + 10.0, is.oz + 97.0, is.ox - 41.0));
            // On a lake island the mountains stand just outside the lake on its raised side, none inside it.
            double where = is.bowlDepth > 0
                    ? smoothstep(-0.45, -0.08, inside) * (1.0 - smoothstep(-0.08, 0.0, inside)) * raised
                    : Math.pow(smoothstep(0.0, 1.0, t), 1.3);
            top += is.peak * where * (0.35 + 0.85 * ridge * ridge);
        }
        double level = Double.NaN;
        if (is.bowlDepth > 0) {
            // Where the middle dips below the water level, there's lake. Noise moves the shore and lifts islets.
            // The raised side: a plateau from a little way back, then a cliff down into the lake.
            top += (is.cliff + is.rim) * raised * smoothstep(-0.4, -0.22, inside) * (1.0 - smoothstep(-0.03, 0.01, inside));
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
        }
        if (is.terrace > 0) top = ground + Math.floor((top - ground) / is.terrace) * is.terrace;
        // A slight drop at the very edge makes a crisp cliff lip.
        top -= 1.5 * (1.0 - smoothstep(0.0, 0.04, t));
        double nearLake = 0.0, out = Double.NaN;
        if (is.bowlDepth > 0) {
            // A lake holds its water. Within lakeBank blocks outside its water area the land is at least a block above
            // the water, then falls away no steeper than 1:1; inside, the shore stays dry for lakeShore blocks, then goes
            // down into the lake no steeper than 1:1. Without this the shore noise could leave dry ground below the
            // water beside it, which the game shows as water walls and dry pits next to the lake. The gentle profile
            // also keeps the 4-block cells the game interpolates from dipping under the water line beside the lake.
            // Any air under the water line in the water area fills with water.
            out = lakeOutside(is, x, z, rn, inside);
            double shore = out > -a.lakeShore ? Math.max(0.0, out - a.lakeBank) : -out - a.lakeShore;
            top = Math.max(top, is.waterLevel + 1.0 - shore);
            if (out <= 0) level = is.waterLevel;
            // Crags (3D detail noise) keep out of the lake and its bank, so they can't open a hole beside the water.
            nearLake = 1.0 - smoothstep(a.lakeBank, 2.0 * a.lakeBank + 8.0, out);
        }
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
        if (debugOn && DEBUG.get() != null) {
            DEBUG.get().append(String.format("island at %.0f,%.0f r=%.0f base=%.0f lake=%s rn=%.3f t=%.3f inside=%.3f water=%.0f top=%.1f bottom=%.1f level=%s%n",
                    is.x, is.z, is.r, is.base, is.bowlDepth > 0, rn, t, inside, is.waterLevel, top, bottom, level));
            if (is.bowlDepth > 0) DEBUG.get().append(String.format("    lakeOutside=%.1f blocks%n", lakeOutside(is, x, z, rn, inside)));
        }
        int i = c.count++;
        c.top[i] = B * top;
        c.bottom[i] = B * bottom;
        c.k[i] = is.wild > 0 ? is.wild * smoothstep(0.0, 0.3, t) : 0.0;
        c.lakeNear = Math.max(c.lakeNear, nearLake);
        c.maxK = Math.max(c.maxK, c.k[i]);
        c.mr[i] = t;
        c.yc[i] = is.base;
        c.basinLevel[i] = level;
    }

    // ---- Rivers -------------------------------------------------------------------------------------------------

    /**
     * A river: an outflow from a lake island's lake to open sky, where it pours off as a waterfall, or a link from its
     * lake to a lower lake island's lake. Its course is the cheapest way through low ground (route), smoothed into curves
     * and resampled every 2 blocks: px, pz, with ps the distance along it. levels[i] is the water level (water fills
     * y < level) for s in [i, i + 1). Its channel is cut into whatever land it crosses, every island's, after a column's
     * islands are in place (carveColumn), and links cross open sky on a bridge of land (addBridge).
     */
    private static final class River {
        double[] px, pz, ps;
        int[] levels;
        double hw, depth, len;
        /** The lower lake's island for a link; null for an outflow. */
        Island target;
        /** An outflow: where its course leaves the last land. */
        double edge = Double.NaN;
        /** A link: the stretch of its course a bridge carries, and where it reaches the lower island's land. */
        double bridge0 = Double.NaN, bridge1 = Double.NaN, landB = Double.NaN;
        /** A link that ends in a waterfall: the lip; the water pours off a ledge LEDGE blocks past it. */
        double sW = Double.NaN;
        int upLevel, toLevel, minLevel, maxLevel;
        /** Noise offsets for this river's width and bank variation. */
        double mox, moz;
        /** The course's bounding box, widened by its reach, and the segments near each RCELL-wide cell of it. */
        double x0, z0, x1, z1;
        int[][] cells;
        int cw, ch;

        boolean waterfall() { return !Double.isNaN(sW); }
        boolean bridged() { return !Double.isNaN(bridge0); }
    }

    private static final River NO_RIVER = new River();
    /** For tools: -Dskyislands.riverDebug=true prints why river courses are rejected. */
    private static final boolean RIVER_DEBUG = Boolean.getBoolean("skyislands.riverDebug");

    private static void why(Island A, Island B, String msg) {
        if (RIVER_DEBUG) System.err.printf("river %.0f,%.0f -> %s: %s%n", A.x, A.z, B == null ? "sky" : String.format("%.0f,%.0f", B.x, B.z), msg);
    }
    private static final int RCELL = 16, RTILE = 64;

    /** A waterfall at the end of a link pours off a flat ledge this long past its lip. See addBridge. */
    private static final double LEDGE = 5.0;

    /** How far from its course a river can change the land: its banks, the gorge it cuts, a bridge's shoulders. */
    private double riverReach(River rv) {
        Arch a = p.arch;
        return 1.3 * rv.hw + Math.max(a.riverMaxCut / (MIN_SLOPE * a.riverBankSlope), 9.0) + 6.0;
    }

    /** Banks vary from this fraction of bankSlope up to MIN_SLOPE + 0.9 times it. */
    private static final double MIN_SLOPE = 0.6;

    /** The channel's half-width at s: it narrows and widens along the river, never below MIN_HALF_WIDTH - 0.5. */
    private double width(River rv, double s) {
        return Math.max(MIN_HALF_WIDTH - 0.5, rv.hw * (1.0 + 0.25 * sn(s, 0.0, 41.0, rv.mox + 71.0, rv.moz)));
    }

    private static final double MIN_HALF_WIDTH = 3.5;

    /**
     * Distance from (x, z) to the river's course, and the distance along the course there, into out; false if (x, z) is
     * beyond its reach. Beyond the course's ends the distance is to the end point.
     */
    private static boolean locate(River rv, double x, double z, double[] out) {
        if (x < rv.x0 || x >= rv.x1 || z < rv.z0 || z >= rv.z1) return false;
        int ci = (int) ((x - rv.x0) / RCELL), cj = (int) ((z - rv.z0) / RCELL);
        if (ci >= rv.cw || cj >= rv.ch) return false;
        int[] segs = rv.cells[ci * rv.ch + cj];
        if (segs == null) return false;
        double best = Double.POSITIVE_INFINITY, bs = 0.0;
        for (int k : segs) {
            double ax = rv.px[k], az = rv.pz[k], vx = rv.px[k + 1] - ax, vz = rv.pz[k + 1] - az, l2 = vx * vx + vz * vz;
            double t = l2 > 0.0 ? clamp(((x - ax) * vx + (z - az) * vz) / l2, 0.0, 1.0) : 0.0;
            double ex = ax + t * vx - x, ez = az + t * vz - z, dd = ex * ex + ez * ez;
            if (dd < best) { best = dd; bs = rv.ps[k] + t * (rv.ps[k + 1] - rv.ps[k]); }
        }
        out[0] = Math.sqrt(best);
        out[1] = bs;
        return true;
    }

    private static int levelAt(River rv, double s) {
        return rv.levels[Math.max(0, Math.min(rv.levels.length - 1, (int) Math.floor(s)))];
    }

    /**
     * A key for per-island caches, from its position. Not from its genes: islands in mirror-image cells, such as (1, -1)
     * and (-1, 1), can roll the same genes, noise offsets included.
     */
    private static long islandKey(Island is) {
        return mix(Double.doubleToLongBits(is.x) * 31 ^ Double.doubleToLongBits(is.z));
    }

    private River river(Island is) {
        long key = islandKey(is);
        River rv = caches.rivers.get(key);
        return rv != null ? rv : caches.rivers.computeIfAbsent(key, k -> buildRiver(is));
    }

    /**
     * The land a river meets at (x, z), counting every island, not only its own: {the highest land surface within reach
     * of the water level (NaN: open sky), the level of the lake whose water area this is (NaN: none)}.
     */
    private double[] groundAt(double x, double z, double level) {
        Column t = new Column(this, (int) Math.floor(x), (int) Math.floor(z));
        t.bare = true;
        fillArchipelago(t);
        return ground(t, level);
    }

    /**
     * The same, from a list of the main islands that can reach the area (ProbeArea), instead of searching every grid
     * cell within the biggest island's reach for each probe: planning a river probes the ground thousands of times.
     */
    private double[] groundAt(double x, double z, double level, Island[] mains) {
        Column t = new Column(this, (int) Math.floor(x), (int) Math.floor(z));
        t.bare = true;
        t.count = 0;
        t.channel = Double.NaN;
        // Whole-block columns, as fillArchipelago samples them.
        t.sharedWarp = sharedWarp(t.x, t.z);
        for (Island is : mains) addIsland(t, is, t.x, t.z);
        Arch a = p.arch;
        if (a.pepperChance > 0) for (Island is : pepperTile(Math.floorDiv(t.x, PEPPER_TILE), Math.floorDiv(t.z, PEPPER_TILE))) addIsland(t, is, t.x, t.z);
        return ground(t, level);
    }

    /** The main islands whose land can reach the box: the same cells and reach test fillArchipelago uses. */
    private Island[] mainsIn(double x0, double z0, double x1, double z1) {
        Arch a = p.arch;
        double room = a.sharedRoom();
        java.util.List<Island> out = new java.util.ArrayList<>();
        for (int cx = (int) Math.floor(x0 / a.cell) - a.search; cx <= (int) Math.floor(x1 / a.cell) + a.search; cx++)
            for (int cz = (int) Math.floor(z0 / a.cell) - a.search; cz <= (int) Math.floor(z1 / a.cell) + a.search; cz++)
                addCandidate(out, mainIsland(cx, cz), (int) Math.floor(x0), (int) Math.floor(z0), (int) Math.ceil(x1), (int) Math.ceil(z1), room);
        if (a.leySpacing > 0 && a.leyChance > 0) {
            for (int cx = (int) Math.floor(x0 / a.leyCell) - a.leySearch; cx <= (int) Math.floor(x1 / a.leyCell) + a.leySearch; cx++)
                for (int cz = (int) Math.floor(z0 / a.leyCell) - a.leySearch; cz <= (int) Math.floor(z1 / a.leyCell) + a.leySearch; cz++)
                    addCandidate(out, leyIsland(cx, cz), (int) Math.floor(x0), (int) Math.floor(z0), (int) Math.ceil(x1), (int) Math.ceil(z1), room);
        }
        return out.toArray(new Island[0]);
    }

    private static double[] ground(Column t, double level) {
        double top = Double.NaN, lake = Double.NaN;
        for (int i = 0; i < t.count; i++) {
            double tp = t.top[i] / B, bt = t.bottom[i] / B;
            if (bt > level + 40.0 || tp < level - 60.0) continue;
            if (Double.isNaN(top) || tp > top) top = tp;
            if (!Double.isNaN(t.basinLevel[i])) lake = Double.isNaN(lake) ? t.basinLevel[i] : Math.max(lake, t.basinLevel[i]);
        }
        return new double[]{top, lake};
    }

    /** Open water of the lake at `level`: its water area, with the ground under the water line. */
    private static boolean open(double[] g, double level) {
        return g[1] == level && !Double.isNaN(g[0]) && g[0] <= level - 1.0;
    }

    private River buildRiver(Island is) {
        Arch a = p.arch;
        if (!(is.bowlDepth > 0) || a.riverChance <= 0) return NO_RIVER;
        long h = mix(seedMix ^ islandKey(is) ^ 0x6A09E667F3BCC909L);
        if (unit(h) >= a.riverChance) return NO_RIVER;
        River link = buildLink(is);
        if (link != null) return link;
        River out = course(is, null, h);
        return out != null ? out : NO_RIVER;
    }

    /**
     * Whether lake island A is in a lake district and rolled a link: it then links to the nearest lower lake island in
     * one of the 8 neighbouring cells that a course can reach. Cheap, so river tiles can tell how far A's river may run.
     */
    private boolean mayLink(Island A) {
        Arch a = p.arch;
        if (a.chainChance <= 0) return false;
        int cx = (int) Math.floor(A.x / a.cell), cz = (int) Math.floor(A.z / a.cell);
        if (mainIsland(cx, cz) != A) return false;
        double district = smoothstep(a.chainDistrict - 0.15, a.chainDistrict + 0.15, region(A.x, A.z, 7331.0));
        return unit(mix(seedMix ^ islandKey(A) ^ 0x3243F6A8885A308DL)) < a.chainChance * district;
    }

    private River buildLink(Island A) {
        Arch a = p.arch;
        if (!mayLink(A)) return null;
        int cx = (int) Math.floor(A.x / a.cell), cz = (int) Math.floor(A.z / a.cell);
        java.util.List<Island> cands = new java.util.ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            Island B = mainIsland(cx + dx, cz + dz);
            if (B == NO_ISLAND || !(B.bowlDepth > 0) || B.waterLevel >= A.waterLevel) continue;
            if (A.waterLevel - B.waterLevel > a.chainMaxDrop || Math.hypot(B.x - A.x, B.z - A.z) > a.chainMaxLength) continue;
            cands.add(B);
        }
        cands.sort(java.util.Comparator.comparingDouble(B -> Math.hypot(B.x - A.x, B.z - A.z)));
        long h = mix(seedMix ^ islandKey(A) ^ 0x13198A2E03707344L);
        for (Island B : cands) {
            River rv = course(A, B, h);
            if (rv != null) return rv;
        }
        return null;
    }

    /**
     * The cheapest way from A's lake through low ground, as points on a 6-block grid: to open sky for an outflow (B
     * null), or into B's lake for a link. Moving through land costs more the higher the land stands over A's water
     * (the gorge the river would cut), plus a slow noise so courses wander over flat ground instead of running straight;
     * open sky costs a link three times plain land (a bridge), and other lakes are avoided. Null if nothing is found
     * within the islands' reach.
     */
    private java.util.List<double[]> route(Island A, Island B, double mox, double moz) {
        Arch a = p.arch;
        final double G = 6.0, LA = A.waterLevel, LB = B == null ? Double.NaN : B.waterLevel;
        double bx0 = A.x - A.reach, bz0 = A.z - A.reach, bx1 = A.x + A.reach, bz1 = A.z + A.reach;
        if (B != null) {
            bx0 = Math.min(bx0, B.x - B.reach); bz0 = Math.min(bz0, B.z - B.reach);
            bx1 = Math.max(bx1, B.x + B.reach); bz1 = Math.max(bz1, B.z + B.reach);
        }
        Island[] mains = mainsIn(bx0, bz0, bx1, bz1);
        // From open water near A's centre to (for a link) open water near B's centre: a lake's water area can be split
        // by another island's land, and the river should join the main body, not a side pond.
        double[] from = lakeSeed(A, G, LA, mains), to = B == null ? null : lakeSeed(B, G, LA, mains);
        if (from == null || (B != null && to == null)) { why(A, B, "no open water found near a lake's centre"); return null; }
        double sx = from[0], sz = from[1];
        java.util.Map<Long, double[]> info = new java.util.HashMap<>();
        java.util.Map<Long, Double> dist = new java.util.HashMap<>();
        java.util.Map<Long, Long> prev = new java.util.HashMap<>();
        java.util.PriorityQueue<double[]> pq = new java.util.PriorityQueue<>(java.util.Comparator.comparingDouble(e -> e[0]));
        long start = 0L;
        dist.put(start, 0.0);
        pq.add(new double[]{0.0, start});
        long goal = Long.MIN_VALUE;
        int visits = 0;
        while (!pq.isEmpty()) {
            double[] e = pq.poll();
            long key = (long) e[1];
            if (e[0] > dist.getOrDefault(key, Double.POSITIVE_INFINITY)) continue;
            int i = (int) (key >> 32), j = (int) key;
            double[] g = info.get(key);
            if (key != start && g != null) {
                if (B == null ? Double.isNaN(g[0]) : open(g, LB) && Math.hypot(sx + i * G - to[0], sz + j * G - to[1]) <= 1.5 * G) { goal = key; break; }
            }
            if (++visits > 25000) { why(A, B, "search gave up after 25000 cells"); return null; }
            for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
                if (di == 0 && dj == 0) continue;
                int ni = i + di, nj = j + dj;
                double x = sx + ni * G, z = sz + nj * G;
                if (x < bx0 || x > bx1 || z < bz0 || z > bz1) continue;
                long nk = (long) ni << 32 | (nj & 0xFFFFFFFFL);
                double[] ng = info.get(nk);
                if (ng == null) info.put(nk, ng = groundAt(x, z, LA, mains));
                double w;
                if (open(ng, LA) || (B != null && open(ng, LB))) w = 0.05;
                else if (Double.isNaN(ng[0])) {
                    if (B == null) w = 1.0;
                    else w = 3.0;
                } else if (!Double.isNaN(ng[1]) && ng[1] != LA && ng[1] != LB) w = 25.0;
                else {
                    double cut = Math.max(0.0, ng[0] - LA);
                    w = 1.0 + 0.6 * cut + (cut > a.riverMaxCut ? 40.0 : 0.0)
                            + 1.6 * (0.5 + 0.5 * sn(x, z, 64.0, mox, moz)) + 0.8 * (0.5 + 0.5 * sn(x, z, 23.0, moz, mox));
                }
                double nd = e[0] + w * G * (di != 0 && dj != 0 ? Math.sqrt(2.0) : 1.0);
                if (nd < dist.getOrDefault(nk, Double.POSITIVE_INFINITY)) {
                    dist.put(nk, nd);
                    prev.put(nk, key);
                    pq.add(new double[]{nd, nk});
                }
            }
        }
        if (goal == Long.MIN_VALUE) { why(A, B, "no route within reach (" + visits + " cells)"); return null; }
        java.util.ArrayList<double[]> path = new java.util.ArrayList<>();
        for (Long k = goal; k != null; k = prev.get(k)) {
            int i = (int) (k >> 32), j = (int) (long) k;
            double[] g = k == start ? groundAt(sx, sz, LA, mains) : info.get(k);
            path.add(new double[]{sx + i * G, sz + j * G, open(g, LA) ? 1.0 : 0.0});
        }
        java.util.Collections.reverse(path);
        // The whole way from the lake's open water near its centre: the channel then also cuts through any low ridge
        // between the main body and a side pond the route passes through.
        return path;
    }

    /** Open water of the island's lake, the nearest found spiralling out from its centre; null if none. */
    private double[] lakeSeed(Island is, double G, double level, Island[] mains) {
        for (int ring = 0; ring * G < is.r; ring++) {
            int n = Math.max(1, 8 * ring);
            for (int k = 0; k < n; k++) {
                double ang = 2.0 * Math.PI * k / n, x = is.x + ring * G * Math.cos(ang), z = is.z + ring * G * Math.sin(ang);
                if (open(groundAt(x, z, level, mains), is.waterLevel)) return new double[]{x, z};
            }
        }
        return null;
    }

    /** Chaikin's corner cutting, twice, keeping the end points: the grid route's corners become curves. */
    private static java.util.List<double[]> smooth(java.util.List<double[]> pts) {
        for (int pass = 0; pass < 2; pass++) {
            java.util.List<double[]> out = new java.util.ArrayList<>();
            out.add(pts.get(0));
            for (int k = 0; k + 1 < pts.size(); k++) {
                double[] u = pts.get(k), v = pts.get(k + 1);
                out.add(new double[]{0.75 * u[0] + 0.25 * v[0], 0.75 * u[1] + 0.25 * v[1]});
                out.add(new double[]{0.25 * u[0] + 0.75 * v[0], 0.25 * u[1] + 0.75 * v[1]});
            }
            out.add(pts.get(pts.size() - 1));
            pts = out;
        }
        return pts;
    }

    /**
     * Builds a river from A's lake along route(): an outflow if B is null, else a link to B's lake. Null if there is no
     * route, or it would cut a gorge deeper than maxCut (plus a little) or bridge more open sky than maxGap.
     */
    private River course(Island A, Island B, long h) {
        Arch a = p.arch;
        double mox = (unit(h = mix(h)) - 0.5) * 40000.0, moz = (unit(h = mix(h)) - 0.5) * 40000.0;
        java.util.List<double[]> grid = route(A, B, mox, moz);
        if (grid == null || grid.size() < 3) { if (grid != null) why(A, B, "route too short"); return null; }
        java.util.List<double[]> pts = smooth(grid);
        // Resample every 2 blocks.
        java.util.List<double[]> rs = new java.util.ArrayList<>();
        rs.add(new double[]{pts.get(0)[0], pts.get(0)[1], 0.0});
        double carry = 0.0, total = 0.0;
        for (int k = 0; k + 1 < pts.size(); k++) {
            double[] u = pts.get(k), v = pts.get(k + 1);
            double seg = Math.hypot(v[0] - u[0], v[1] - u[1]);
            double t = 2.0 - carry;
            while (t <= seg) {
                double f = t / seg;
                rs.add(new double[]{u[0] + f * (v[0] - u[0]), u[1] + f * (v[1] - u[1]), total + t});
                t += 2.0;
            }
            carry = seg - (t - 2.0);
            total += seg;
        }
        // The end point too: it is the open sky or the lower lake the route found, and a sample 2 blocks short of it can
        // still be on land.
        double[] endPt = pts.get(pts.size() - 1);
        if (total - rs.get(rs.size() - 1)[2] > 0.25) rs.add(new double[]{endPt[0], endPt[1], total});
        // Meanders: the smoothed course swings from side to side, a few channel-widths each way, repeating every 40 to 80
        // blocks, fading out near both ends so it still leaves and enters lakes where the route does.
        // At least 7 blocks across: a narrower channel is partly filled in by the game's 4-block cells and breaks up.
        double hw0 = clamp(a.riverHalfWidth * Math.sqrt((B == null ? A.r : Math.min(A.r, B.r)) / 100.0), MIN_HALF_WIDTH, 2.0 * a.riverHalfWidth);
        double amp = clamp(a.riverMeander * 2.5 * hw0, 4.0, 14.0), wl = 40.0 + 40.0 * unit(h = mix(h));
        java.util.List<double[]> bent = new java.util.ArrayList<>();
        for (int k = 0; k < rs.size(); k++) {
            double[] u = rs.get(Math.max(0, k - 1)), v = rs.get(Math.min(rs.size() - 1, k + 1)), q = rs.get(k);
            double tx = v[0] - u[0], tz = v[1] - u[1], tl = Math.hypot(tx, tz);
            double sk = q[2], env = smoothstep(4.0, 20.0, sk) * smoothstep(4.0, 20.0, total - sk);
            double off = tl > 0.0 ? amp * env * (sn(sk, 0.0, wl, mox + 907.0, moz) + 0.4 * sn(sk, 0.0, 0.37 * wl, moz - 311.0, mox)) : 0.0;
            bent.add(new double[]{q[0] - off * tz / Math.max(tl, 1e-9), q[1] + off * tx / Math.max(tl, 1e-9), 0.0});
        }
        double along = 0.0;
        for (int k = 1; k < bent.size(); k++) {
            along += Math.hypot(bent.get(k)[0] - bent.get(k - 1)[0], bent.get(k)[1] - bent.get(k - 1)[1]);
            bent.get(k)[2] = along;
        }
        rs = bent;
        total = along;
        double LA = A.waterLevel, LB = B == null ? Double.NaN : B.waterLevel;
        int n = rs.size();
        double[][] g = new double[n][];
        double cx0 = Double.POSITIVE_INFINITY, cz0 = Double.POSITIVE_INFINITY, cx1 = Double.NEGATIVE_INFINITY, cz1 = Double.NEGATIVE_INFINITY;
        for (double[] q : rs) { cx0 = Math.min(cx0, q[0]); cz0 = Math.min(cz0, q[1]); cx1 = Math.max(cx1, q[0]); cz1 = Math.max(cz1, q[1]); }
        Island[] mains = mainsIn(cx0, cz0, cx1, cz1);
        for (int k = 0; k < n; k++) g[k] = groundAt(rs.get(k)[0], rs.get(k)[1], LA, mains);
        // Where the course leaves A's lake for good (its water area, shore strip included), plus 4 blocks, since the
        // lake's edge isn't square to the course: the water stays at the lake's level until then.
        int lastLake = 0;
        for (int k = 0; k < n; k++) if (g[k][1] == LA) lastLake = k;
        double dry = rs.get(Math.min(n - 1, lastLake + 1))[2] + 4.0;
        int endK;
        double fv = Double.NaN, lv = Double.NaN, cut = 0.0;
        if (B == null) {
            endK = -1;
            for (int k = lastLake + 1; k < n; k++) if (Double.isNaN(g[k][0])) { endK = k; break; }
            if (endK < 0) { why(A, B, "course never reaches open sky"); return null; }
        } else {
            endK = -1;
            for (int k = lastLake + 1; k < n; k++) {
                if (open(g[k], LB) && g[k][0] <= LB - 3.0) { endK = k; break; }
                // Open sky, or land too far below B's level to hold the water up: a bridge carries it.
                if (g[k][1] != LB && (Double.isNaN(g[k][0]) || g[k][0] < LB - 1.0)) { if (Double.isNaN(fv)) fv = rs.get(k)[2]; lv = rs.get(k)[2]; }
            }
            if (endK < 0) { why(A, B, "course never reaches the lower lake"); return null; }
        }
        for (int k = lastLake + 1; k < endK; k++) if (!Double.isNaN(g[k][0])) cut = Math.max(cut, g[k][0] - LA);
        if (cut > a.riverMaxCut * 1.3) { why(A, B, String.format("gorge %.0f deep", cut)); return null; }
        if (!Double.isNaN(fv) && lv - fv + 2.0 > a.chainMaxGap) { why(A, B, String.format("bridge %.0f long", lv - fv + 2.0)); return null; }

        River rv = new River();
        rv.target = B;
        rv.hw = hw0 * (0.85 + 0.3 * unit(h = mix(h)));
        rv.mox = mox;
        rv.moz = moz;
        rv.depth = a.riverDepth * (0.75 + 0.5 * unit(h = mix(h)));
        double end = rs.get(endK)[2];
        rv.len = (B == null ? end : total) + 2.0;
        int count = (int) Math.ceil(rv.len) + 1;
        int[] levels = new int[count];
        int top = (int) Math.round(LA);
        if (B == null) {
            // An outflow: steps a block lower every few blocks, evenly from the lake's level down to a block under the
            // land at the edge; steeper land beside it pulls it down faster, into small cascades.
            rv.edge = end;
            int last = top;
            for (int k = Math.max(lastLake + 1, endK - 3); k < endK; k++) if (!Double.isNaN(g[k][0])) last = Math.min(last, (int) Math.floor(g[k][0]) - 1);
            double spacing = top > last ? clamp((end - dry) / (top - last), a.riverStepMin, a.riverStepMax) : Double.POSITIVE_INFINITY;
            int L = top;
            double next = dry + spacing;
            int k = 0;
            for (int i = 0; i < count; i++) {
                while (k + 1 < n && rs.get(k + 1)[2] <= i) k++;
                if (i >= dry && k < endK && !Double.isNaN(g[k][0]) && g[k][1] != LA) {
                    if (i >= next && L > last) { L--; next = i + spacing; }
                    L = Math.min(L, (int) Math.floor(g[k][0]) - 1);
                }
                levels[i] = L;
            }
        } else {
            // A link. Gentle: the water steps down a block every `spacing` blocks and is at B's level by the time it
            // reaches B's land. Steep: it steps down at a middling rate, and the rest of the drop is a waterfall into a
            // plunge pool, off a bridge's end over B's land, or over a cliff cut into the land if there is no open sky.
            rv.toLevel = (int) Math.round(LB);
            double landB = Double.isNaN(lv) ? Double.NaN : lv + 2.0;
            double reachB = !Double.isNaN(landB) ? landB : end - 8.0;
            double run = reachB - dry, drop = LA - LB;
            boolean steep = run / drop < a.riverStepMin;
            double spacing = steep ? 0.5 * (a.riverStepMin + a.riverStepMax) : Math.min(run / drop, a.riverStepMax);
            if (steep) rv.sW = !Double.isNaN(landB) ? landB + rv.hw + 5.0 : Math.max(dry + 8.0, end - LEDGE - 10.0);
            // The water also follows the land down into dips (a block under it), never rising again and never below B.
            int L = top, k = 0;
            for (int i = 0; i < count; i++) {
                while (k + 1 < n && rs.get(k + 1)[2] <= i) k++;
                L = Math.min(L, top - (i < dry ? 0 : (int) Math.floor((i - dry) / spacing + 1e-9)));
                if (i >= dry && k < endK && !Double.isNaN(g[k][0]) && g[k][1] != LA && g[k][1] != LB) L = Math.min(L, (int) Math.floor(g[k][0]) - 1);
                if (k >= endK) L = rv.toLevel;  // on through B's water, cutting any ridge in it, at B's level
                L = Math.max(L, rv.toLevel);
                levels[i] = steep && i >= rv.sW ? rv.toLevel : L;
            }
            // Following dips down can leave the river cutting deeper gorges later on.
            double deep = 0.0;
            for (int q = lastLake + 1; q < endK; q++) if (!Double.isNaN(g[q][0])) deep = Math.max(deep, g[q][0] - levels[Math.min(count - 1, (int) rs.get(q)[2])]);
            if (deep > a.riverMaxCut * 1.5) { why(A, B, String.format("gorge %.0f deep after following dips", deep)); return null; }
            rv.landB = !Double.isNaN(landB) ? landB : end;
            if (!Double.isNaN(fv)) {
                rv.bridge0 = fv - 6.0;
                rv.bridge1 = steep ? rv.sW + LEDGE : Math.max(landB + 4.0, Math.min(landB + 12.0, end - 2.0));
            }
            if (steep) rv.upLevel = levels[Math.max(0, (int) Math.floor(rv.sW) - 1)];
        }
        rv.levels = levels;
        rv.minLevel = Integer.MAX_VALUE;
        rv.maxLevel = Integer.MIN_VALUE;
        for (int l : levels) { rv.minLevel = Math.min(rv.minLevel, l); rv.maxLevel = Math.max(rv.maxLevel, l); }
        // The course itself, out to its end, and an index of its segments by cell.
        int m = B == null ? Math.min(n, endK + 2) : n;
        rv.px = new double[m];
        rv.pz = new double[m];
        rv.ps = new double[m];
        for (int k = 0; k < m; k++) { rv.px[k] = rs.get(k)[0]; rv.pz[k] = rs.get(k)[1]; rv.ps[k] = rs.get(k)[2]; }
        double R = riverReach(rv);
        rv.x0 = Double.POSITIVE_INFINITY; rv.z0 = Double.POSITIVE_INFINITY; rv.x1 = Double.NEGATIVE_INFINITY; rv.z1 = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < m; k++) {
            rv.x0 = Math.min(rv.x0, rv.px[k] - R); rv.z0 = Math.min(rv.z0, rv.pz[k] - R);
            rv.x1 = Math.max(rv.x1, rv.px[k] + R); rv.z1 = Math.max(rv.z1, rv.pz[k] + R);
        }
        rv.cw = (int) Math.ceil((rv.x1 - rv.x0) / RCELL) + 1;
        rv.ch = (int) Math.ceil((rv.z1 - rv.z0) / RCELL) + 1;
        java.util.List<java.util.List<Integer>> lists = new java.util.ArrayList<>();
        for (int c = 0; c < rv.cw * rv.ch; c++) lists.add(null);
        for (int k = 0; k + 1 < m; k++) {
            int i0 = (int) ((Math.min(rv.px[k], rv.px[k + 1]) - R - rv.x0) / RCELL), i1 = (int) ((Math.max(rv.px[k], rv.px[k + 1]) + R - rv.x0) / RCELL);
            int j0 = (int) ((Math.min(rv.pz[k], rv.pz[k + 1]) - R - rv.z0) / RCELL), j1 = (int) ((Math.max(rv.pz[k], rv.pz[k + 1]) + R - rv.z0) / RCELL);
            for (int i = Math.max(0, i0); i <= Math.min(rv.cw - 1, i1); i++)
                for (int j = Math.max(0, j0); j <= Math.min(rv.ch - 1, j1); j++) {
                    java.util.List<Integer> l = lists.get(i * rv.ch + j);
                    if (l == null) lists.set(i * rv.ch + j, l = new java.util.ArrayList<>());
                    l.add(k);
                }
        }
        rv.cells = new int[rv.cw * rv.ch][];
        for (int c = 0; c < lists.size(); c++) if (lists.get(c) != null) rv.cells[c] = lists.get(c).stream().mapToInt(Integer::intValue).toArray();
        return rv;
    }

    /**
     * The rivers whose reach overlaps an RTILE-wide square. A lake island's river runs no farther from it than its own
     * reach, or for a link than the link's length plus the biggest island's reach, so only lake islands that close are
     * asked, and only those rivers are worked out.
     */
    private River[] riverTile(int tx, int tz) {
        long key = mix((tx & 0x3FFFFFFL) | (tz & 0x3FFFFFFL) << 26);
        River[] t = caches.riverTiles.get(key);
        return t != null ? t : caches.riverTiles.computeIfAbsent(key, k -> {
            Arch a = p.arch;
            int x0 = tx * RTILE, z0 = tz * RTILE, x1 = x0 + RTILE, z1 = z0 + RTILE;
            // As far as any river's banks can reach from its course (riverReach at the widest a river gets), plus room.
            double pad = 1.3 * 2.0 * a.riverHalfWidth + Math.max(a.riverMaxCut / (MIN_SLOPE * a.riverBankSlope), 9.0) + 6.0 + 8.0;
            double far = a.search * a.cell + (a.chainChance > 0 ? a.chainMaxLength : 0.0) + pad;
            java.util.List<River> out = new java.util.ArrayList<>();
            for (int cx = (int) Math.floor((x0 - far) / a.cell); cx <= (int) Math.floor((x1 + far) / a.cell); cx++)
                for (int cz = (int) Math.floor((z0 - far) / a.cell); cz <= (int) Math.floor((z1 + far) / a.cell); cz++) {
                    Island A = mainIsland(cx, cz);
                    if (A == NO_ISLAND || !(A.bowlDepth > 0)) continue;
                    double r = (mayLink(A) ? a.chainMaxLength + a.search * a.cell : A.reach) + pad;
                    double dx = Math.max(0.0, Math.max(x0 - A.x, A.x - x1)), dz = Math.max(0.0, Math.max(z0 - A.z, A.z - z1));
                    if (dx * dx + dz * dz >= r * r) continue;
                    River rv = river(A);
                    if (rv != NO_RIVER && rv.x0 < x1 && rv.x1 > x0 && rv.z0 < z1 && rv.z1 > z0) out.add(rv);
                }
            return out.toArray(new River[0]);
        });
    }

    /**
     * A link's bridge where its course crosses open sky: a ribbon of land with the channel on top, a flat 4-block
     * towpath a block above the water each side, rounded shoulders, and an underside that thickens where it meets either
     * island and drips. Water is only placed in the channel: the shoulders fall away below the water line, and water
     * there would pour off the sides. A waterfall pours off a flat ledge LEDGE blocks long past the lip, level with the
     * channel's last, one-block-deep stretch: without it the bridge's end is open sky within one of the game's 4-block
     * cells of the water, the blend sags the end below the water line, and water hangs in the air past it.
     */
    private void addBridge(Column c, River rv, double d, double s) {
        if (s < rv.bridge0 || s >= rv.bridge1) return;
        double hw = width(rv, s), W = hw + 7.0;
        if (d >= W || c.count >= c.top.length) return;
        boolean ledge = rv.waterfall() && s >= rv.sW;
        double L = ledge ? rv.upLevel : levelAt(rv, s), u = d / hw;
        double lip = rv.waterfall() ? smoothstep(0.0, 6.0, rv.sW - s) : 1.0;
        double top = ledge ? (d < hw ? L - 1.0 : d < hw + 4.0 ? L + 1.0 : L + 1.0 - 1.5 * (d - hw - 4.0))
                : d < hw ? L - 1.0 - (rv.depth - 1.0) * (1.0 - u * u) * lip
                : d < hw + 4.0 ? L + 1.0 : L + 1.0 - 1.5 * (d - hw - 4.0);
        double ends = Math.min(s - rv.bridge0 - 6.0, rv.landB - s);
        double thick = 5.0 + 3.0 * (0.5 + 0.5 * sn(s, 0.0, 40.0, 311.0 + rv.minLevel, 17.0)) + 12.0 * (1.0 - smoothstep(0.0, 16.0, ends));
        double drip = 4.0 * Math.max(0.0, sn(c.x, c.z, 7.0, -509.0, 211.0));
        double bottom = L - rv.depth - thick * Math.sqrt(Math.max(0.0, 1.0 - (d / W) * (d / W))) - drip;
        if (top <= bottom + 1.0) return;
        int i = c.count++;
        c.top[i] = B * top;
        c.bottom[i] = B * bottom;
        c.k[i] = 0.0;
        c.mr[i] = -1.0;  // a bridge: carveColumn leaves it alone
        c.yc[i] = L;
        c.basinLevel[i] = !ledge && d < hw + 1.5 ? L : Double.NaN;
        c.lakeNear = Math.max(c.lakeNear, 1.0 - smoothstep(hw + 4.0, hw + 16.0, d));
    }

    /**
     * Cuts a river's channel into the land at this column, on every island's span near the water's level (not
     * bridges, not islands far above or below): the bed, deepest in the middle; banks that climb from a block above the
     * water no steeper than bankSlope, which cuts a gorge through higher land; and where the land beside the river is
     * lower than its water, a berm that holds the water in. An outflow runs one block deep over its last few blocks to
     * the edge, so only its surface pours off and no water stands against the open side of the island. A link's
     * waterfall drops off a flat ledge into a plunge pool at the lower lake's level.
     */
    private void carveColumn(Column c, River rv, double d, double s) {
        Arch a = p.arch;
        if (s > rv.len + 2.0) return;
        double L, poolDepth = 0.0;
        boolean ledge = false;
        if (rv.waterfall() && s >= rv.sW) {
            ledge = s < rv.sW + LEDGE;
            L = ledge ? rv.upLevel : rv.toLevel;
            if (!ledge) poolDepth = 3.0 * (1.0 - smoothstep(3.0, 8.0, Math.abs(s - rv.sW - LEDGE)));
        } else {
            L = levelAt(rv, s);
        }
        double lip = rv.target == null ? smoothstep(0.0, 6.0, rv.edge - s) : rv.waterfall() && s < rv.sW ? smoothstep(0.0, 6.0, rv.sW - s) : 1.0;
        // Where the water drops 2 or more blocks at once (following a dip in the land), the channel shallows to a block
        // deep before it, so only the surface spills over; deeper water would stand against the air over the drop.
        int i0 = Math.max(0, Math.min(rv.levels.length - 1, (int) Math.floor(s)));
        for (int j = i0 + 1; j < Math.min(rv.levels.length, i0 + 9); j++) {
            if (rv.levels[j] <= rv.levels[i0] - 2) { lip = Math.min(lip, smoothstep(0.0, 6.0, j - s)); break; }
        }
        double hw = width(rv, s);
        // Uneven banks: the slope varies over the land and the bank's edge is roughened, so a gorge's sides don't step
        // up in even parallel stripes.
        double slope = a.riverBankSlope * (MIN_SLOPE + 0.9 * (0.5 + 0.5 * sn(c.x, c.z, 21.0, rv.mox - 131.0, rv.moz + 57.0)));
        double db = Math.max(hw, d + 1.5 * sn(c.x, c.z, 7.0, rv.mox + 401.0, rv.moz - 222.0));
        for (int i = 0; i < c.count; i++) {
            if (c.mr[i] < 0.0) continue;
            double top = c.top[i] / B, bottom = c.bottom[i] / B;
            if (bottom > L + a.riverMaxCut + 4.0 || top < L - rv.depth - 12.0) continue;
            if (ledge) {
                // A notch as wide as the channel, walled each side, so the waterfall leaves at the river's width.
                if (d < hw) top = Math.min(top, L - 1.0);
                else {
                    top = Math.min(top, L + 1.0 + (db - hw) * slope);
                    if (d < hw + 3.0 && top < L + 1.0 && top > L - 12.0) top = L + 1.0;
                }
            } else if (d < hw) {
                double u = d / hw;
                top = Math.min(top, L - 1.0 - ((rv.depth - 1.0) * lip + poolDepth) * (1.0 - u * u));
            } else {
                top = Math.min(top, L + 1.0 + (db - hw) * slope);
                // A berm, outside any lake's water area, where the land is lower than the water but not far below it.
                if (Double.isNaN(c.basinLevel[i]) && top < L + 1.0 && top > L - 12.0) top = Math.max(top, L + 1.0 - Math.max(0.0, d - hw - 2.0));
            }
            if (top < bottom + 3.0) c.bottom[i] = B * (top - 3.0);
            c.top[i] = B * top;
            // Water fills the channel, and any air under the level a little past the banks too, where the game's 4-block
            // cells blend the channel's corners into the bank and dip it under the water line. Not a bank that really is
            // lower than the water (a hillside below a berm): water there would stand above the land beside it.
            boolean fill = d < hw || (d < hw + 6.0 && top >= L - 1.0);
            if (!ledge && fill && Double.isNaN(c.basinLevel[i]) && top <= L + 1.0 && top >= L - rv.depth - 3.0) c.basinLevel[i] = L;
        }
        c.lakeNear = Math.max(c.lakeNear, 1.0 - smoothstep(hw + 4.0, hw + 16.0, d));
    }

    // ---- Land bridges --------------------------------------------------------------------------------------------

    /**
     * A natural stone arch between two neighbouring main islands, for crossing on foot or by horse: a grassy deck from
     * a little way inside one island's edge to a little way inside the other's, its surface easing from one island's
     * ground height to the other's with a slight rise in the middle and a gentle bend. It is broad and deep where it
     * leaves each island and narrows to 2w wide and `thick` deep in the middle of the span, both along a cubic curve,
     * so most of the span is slender and the ends flare out into the islands.
     */
    private static final class LandBridge {
        double ax, az, dx, dz, len, gap, hA, hB, w, wEnd, thick, thickEnd, amp, wl, nox, noz, arch;
        double x0, z0, x1, z1, minY, maxY;
    }

    /** How far inside each island's edge a land bridge starts; its ends are rounded off over this length. */
    private static final double LB_INSET = 16.0;

    private static final LandBridge NO_BRIDGE = new LandBridge();

    /** Island `is` alone at (x, z): its land's top, or NaN where it has none. */
    private double ownTop(Island is, double x, double z) {
        Column t = new Column(this, (int) Math.floor(x), (int) Math.floor(z));
        t.sharedWarp = sharedWarp(t.x, t.z);
        addIsland(t, is, t.x, t.z);
        return t.count == 0 ? Double.NaN : t.top[0] / B;
    }

    /**
     * The land bridge a pair of islands could have, or NO_BRIDGE where they touch already, stand too far apart or differ
     * too much in height to ride across; cached per pair, the same whichever island asks.
     */
    private LandBridge landBridge(Island A, Island B) {
        if (islandKey(A) > islandKey(B)) { Island t = A; A = B; B = t; }
        Island fa = A, fb = B;
        long key = mix(islandKey(A) * 31 ^ islandKey(B) ^ 0x5BE0CD19137E2179L);
        LandBridge lb = caches.landBridges.get(key);
        return lb != null ? lb : caches.landBridges.computeIfAbsent(key, k -> buildLandBridge(fa, fb));
    }

    /** Islands that touch a neighbour need no bridge to it: this marks such a pair. */
    private static final LandBridge TOUCHING = new LandBridge();

    /**
     * The neighbours main island `is` bridges to: its best one (the shortest gap, then the gentlest slope), most of the
     * time, and now and then its second best as well; less often if it already touches a neighbour. Each island picking
     * one or two makes chains and branching trees across the sky, about two bridges per island, not a mesh.
     */
    private Island[] bridgePicks(Island is) {
        long key = islandKey(is) ^ 0x2545F4914F6CDD1DL;
        Island[] picks = caches.bridgePicks.get(key);
        return picks != null ? picks : caches.bridgePicks.computeIfAbsent(key, k -> {
            Arch a = p.arch;
            int cx = (int) Math.floor(is.x / a.cell), cz = (int) Math.floor(is.z / a.cell);
            if (mainIsland(cx, cz) != is) return new Island[0];
            long h = mix(seedMix ^ islandKey(is) ^ 0x1F83D9ABFB41BD6BL);
            java.util.List<Object[]> valid = new java.util.ArrayList<>();
            boolean touches = false;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Island nb = mainIsland(cx + dx, cz + dz);
                if (nb == NO_ISLAND) continue;
                LandBridge lb = landBridge(is, nb);
                if (lb == TOUCHING) touches = true;
                else if (lb != NO_BRIDGE) valid.add(new Object[]{nb, lb.len + 3.0 * Math.abs(lb.hA - lb.hB)});
            }
            valid.sort(java.util.Comparator.comparingDouble(o -> (Double) o[1]));
            // Some stretches of sky are well bridged, others more sparsely.
            double regional = 0.6 + 0.8 * region(is.x, is.z, 8117.0);
            double first = a.landBridgeChance * regional * (touches ? 0.5 : 1.0), second = a.landBridgeSecond * regional;
            java.util.List<Island> out = new java.util.ArrayList<>();
            if (valid.size() > 0 && unit(h = mix(h)) < first) out.add((Island) valid.get(0)[0]);
            if (valid.size() > 1 && unit(h = mix(h)) < second) out.add((Island) valid.get(1)[0]);
            // Two islands that are each other's best neighbour would make a lone pair: the one with the larger key
            // also reaches on to its next best, so pairs join into chains.
            if (out.size() == 1 && valid.size() > 1 && islandKey(is) > islandKey(out.get(0)) && bestNeighbour(out.get(0)) == is)
                out.add((Island) valid.get(1)[0]);
            return out.toArray(new Island[0]);
        });
    }

    /** The neighbour main island `is` could best bridge to, ignoring chance; null if none. */
    private Island bestNeighbour(Island is) {
        Arch a = p.arch;
        int cx = (int) Math.floor(is.x / a.cell), cz = (int) Math.floor(is.z / a.cell);
        Island best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            Island nb = mainIsland(cx + dx, cz + dz);
            if (nb == NO_ISLAND) continue;
            LandBridge lb = landBridge(is, nb);
            if (lb == NO_BRIDGE || lb == TOUCHING) continue;
            double score = lb.len + 3.0 * Math.abs(lb.hA - lb.hB);
            if (score < bestScore) { bestScore = score; best = nb; }
        }
        return best;
    }

    private static void whyBridge(String msg) {
        if (RIVER_DEBUG) System.err.println("land bridge pair: " + msg);
    }

    private LandBridge buildLandBridge(Island A, Island B) {
        Arch a = p.arch;
        long h = mix(seedMix ^ islandKey(A) * 7 ^ islandKey(B) ^ 0x1F83D9ABFB41BD6BL);
        double D = Math.hypot(B.x - A.x, B.z - A.z), dx = (B.x - A.x) / D, dz = (B.z - A.z) / D;
        // Along the line between their centres: where A's land ends and B's begins.
        double sA1 = Double.NaN, sB0 = Double.NaN;
        for (double s = 0.0; s <= D; s += 2.0) {
            double x = A.x + s * dx, z = A.z + s * dz;
            if (Double.isNaN(sA1)) { if (Double.isNaN(ownTop(A, x, z))) sA1 = s; }
            else if (!Double.isNaN(ownTop(B, x, z))) { sB0 = s; break; }
        }
        if (Double.isNaN(sA1)) { whyBridge("touching (A's land reaches B's centre)"); return TOUCHING; }
        if (Double.isNaN(sB0)) { whyBridge("B's land not on the line"); return NO_BRIDGE; }
        double gap = sB0 - sA1;
        if (gap < 6.0) { whyBridge("touching"); return TOUCHING; }
        if (gap > a.landBridgeMaxGap) { whyBridge("gap " + (int) (gap / 20) * 20); return NO_BRIDGE; }
        double hA = ownTop(A, A.x + (sA1 - 6.0) * dx, A.z + (sA1 - 6.0) * dz), hB = ownTop(B, A.x + (sB0 + 6.0) * dx, A.z + (sB0 + 6.0) * dz);
        if (Double.isNaN(hA) || Double.isNaN(hB)) { whyBridge("no ground at an end"); return NO_BRIDGE; }
        // Gentle enough to ride: at most a block of rise per 2 blocks across, over the span and the ends.
        if (Math.abs(hA - hB) > (gap + 20.0) / 2.0) { whyBridge("too steep"); return NO_BRIDGE; }
        whyBridge("ok");
        LandBridge lb = new LandBridge();
        lb.ax = A.x + (sA1 - LB_INSET) * dx;
        lb.az = A.z + (sA1 - LB_INSET) * dz;
        lb.dx = dx;
        lb.dz = dz;
        lb.len = gap + 2.0 * LB_INSET;
        lb.gap = gap;
        lb.hA = Math.floor(hA);
        lb.hB = Math.floor(hB);
        // Longer spans are heavier: thicker in the middle, and broader and deeper at the ends.
        lb.w = 4.5 + 2.0 * unit(h = mix(h));
        lb.wEnd = lb.w + clamp(0.1 * gap, 6.0, 14.0);
        lb.thick = 6.0 + 0.02 * gap + 1.5 * unit(h = mix(h));
        lb.thickEnd = lb.thick + clamp(0.15 * gap, 10.0, 24.0);
        lb.amp = Math.min(8.0, 0.08 * gap);
        lb.wl = 40.0 + 40.0 * unit(h = mix(h));
        lb.nox = (unit(h = mix(h)) - 0.5) * 40000.0;
        lb.noz = (unit(h = mix(h)) - 0.5) * 40000.0;
        lb.arch = Math.min(3.0, gap / 30.0);
        double R = lb.wEnd + 4.0 + lb.amp;
        double bx = lb.ax + lb.len * dx, bz = lb.az + lb.len * dz;
        lb.x0 = Math.min(lb.ax, bx) - R; lb.x1 = Math.max(lb.ax, bx) + R;
        lb.z0 = Math.min(lb.az, bz) - R; lb.z1 = Math.max(lb.az, bz) + R;
        lb.minY = Math.min(lb.hA, lb.hB) - lb.thickEnd - 10.0;
        lb.maxY = Math.max(lb.hA, lb.hB) + lb.arch + 6.0;
        return lb;
    }

    /** The land bridges whose deck can reach an RTILE-wide square. */
    private LandBridge[] landBridgeTile(int tx, int tz) {
        long key = mix((tx & 0x3FFFFFFL) | (tz & 0x3FFFFFFL) << 26);
        LandBridge[] t = caches.landBridgeTiles.get(key);
        return t != null ? t : caches.landBridgeTiles.computeIfAbsent(key, k -> {
            Arch a = p.arch;
            int x0 = tx * RTILE, z0 = tz * RTILE, x1 = x0 + RTILE, z1 = z0 + RTILE;
            double far = a.search * a.cell + a.landBridgeMaxGap;
            java.util.List<LandBridge> out = new java.util.ArrayList<>();
            for (int cx = (int) Math.floor((x0 - far) / a.cell); cx <= (int) Math.floor((x1 + far) / a.cell); cx++)
                for (int cz = (int) Math.floor((z0 - far) / a.cell); cz <= (int) Math.floor((z1 + far) / a.cell); cz++) {
                    Island A = mainIsland(cx, cz);
                    if (A == NO_ISLAND) continue;
                    // Only islands whose neighbourhood could reach the tile are asked for their picks.
                    double r = 1.5 * a.cell + a.search * a.cell;
                    double ddx = Math.max(0.0, Math.max(x0 - A.x, A.x - x1)), ddz = Math.max(0.0, Math.max(z0 - A.z, A.z - z1));
                    if (ddx * ddx + ddz * ddz >= r * r) continue;
                    for (Island Bi : bridgePicks(A)) {
                        LandBridge lb = landBridge(A, Bi);
                        if (lb != NO_BRIDGE && lb != TOUCHING && !out.contains(lb) && lb.x0 < x1 && lb.x1 > x0 && lb.z0 < z1 && lb.z1 > z0) out.add(lb);
                    }
                }
            return out.toArray(new LandBridge[0]);
        });
    }

    /** The land bridge's span at (x, z), if its deck or body reaches here. */
    private void addLandBridge(Column c, LandBridge lb) {
        double px = c.x - lb.ax, pz = c.z - lb.az, s = px * lb.dx + pz * lb.dz;
        if (s < 0.0 || s > lb.len || c.count >= c.top.length) return;
        double t = s / lb.len, bend = lb.amp * Math.sin(Math.PI * t) * sn(s, 0.0, lb.wl, lb.nox, lb.noz);
        // 0 in the middle of the span, 1 at each island's edge and on inside it; cubed, so the flare is at the ends.
        double u = Math.min(1.0, Math.abs(s - 0.5 * lb.len) / (0.5 * lb.gap)), flare = u * u * u;
        // Inside the island the end is rounded off, not cut square.
        double in = Math.min(s, lb.len - s) / LB_INSET, cap = in >= 1.0 ? 1.0 : Math.sqrt(1.0 - (1.0 - in) * (1.0 - in));
        double w = (lb.w + (lb.wEnd - lb.w) * flare) * cap;
        if (w < 1.0) return;
        double d = Math.abs(-px * lb.dz + pz * lb.dx - bend), W = w + 4.0;
        if (d >= W) return;
        // The deck: easing from one island's height to the other's (from 10 blocks inside each edge), a slight rise in
        // the middle, a slight crown, and rounded edges that fall away beyond the walkway.
        double e = clamp((s - (LB_INSET - 10.0)) / (lb.gap + 20.0), 0.0, 1.0);
        double H = lb.hA + (lb.hB - lb.hA) * smoothstep(0.0, 1.0, e) + lb.arch * Math.sin(Math.PI * e);
        double top = H + 1.0 - 0.3 * (d / w) * (d / w) - (d > w ? 1.5 * (d - w) : 0.0);
        // The underside arches the same way: shallow in the middle, deep where it meets each island; drips underneath.
        double thick = lb.thick + (lb.thickEnd - lb.thick) * flare + 1.5 * sn(s, 0.0, 23.0, lb.noz, lb.nox);
        double drip = 3.0 * Math.max(0.0, sn(c.x, c.z, 7.0, lb.nox - 509.0, lb.noz + 211.0));
        double bottom = H - thick * Math.sqrt(Math.max(0.0, 1.0 - (d / W) * (d / W))) - drip;
        if (top <= bottom + 1.0) return;
        int i = c.count++;
        c.top[i] = B * top;
        c.bottom[i] = B * bottom;
        c.k[i] = 0.0;
        c.mr[i] = -1.0;  // a bridge: rivers don't carve it
        c.yc[i] = H;
        c.basinLevel[i] = Double.NaN;
    }

    /** 0 to 1: how strongly (x, z) is on the raised side of lake island `is`. One slow noise, so the raised part is one or two stretches of shore. */
    private double raisedSide(Island is, double x, double z) {
        Arch a = p.arch;
        return clamp(a.raisedBias + a.raisedGain * sn(x, z, a.raisedWavelength * is.r + 10.0, is.ox + 3203.0, is.oz - 2741.0), 0.0, 1.0);
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

    private static volatile boolean debugOn;
    private static final ThreadLocal<StringBuilder> DEBUG = new ThreadLocal<>();

    /** For tools: each island layer at (x, z) with its shape and lake values, one line per island. */
    public String debugColumn(int x, int z) {
        StringBuilder sb = new StringBuilder();
        debugOn = true;
        DEBUG.set(sb);
        try {
            Column c = new Column(this, x, z);
            fill(c, true, true);
        } finally {
            DEBUG.remove();
        }
        return sb.toString();
    }

    /**
     * For tools: the lake links whose upper lake is in the box, one line each: from and to centres, water levels, the
     * gap between the islands along the path, the bridge's length and whether it ends in a waterfall.
     */
    public java.util.List<String> linksIn(int x0, int z0, int x1, int z1) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Arch a = p.arch;
        if (a == null || a.riverChance <= 0) return out;
        for (int cx = Math.floorDiv(x0, (int) a.cell); cx <= Math.floorDiv(x1, (int) a.cell); cx++)
            for (int cz = Math.floorDiv(z0, (int) a.cell); cz <= Math.floorDiv(z1, (int) a.cell); cz++) {
                Island A = mainIsland(cx, cz);
                if (A == NO_ISLAND || !(A.bowlDepth > 0)) continue;
                River rv = river(A);
                if (rv == NO_RIVER) continue;
                int mid = rv.px.length / 2;
                if (rv.target == null) out.add(String.format("outflow %.0f,%.0f (water %.0f): %.0f blocks, mid %.0f,%.0f, levels %d..%d",
                        A.x, A.z, A.waterLevel, rv.len, rv.px[mid], rv.pz[mid], rv.maxLevel, rv.minLevel));
                else out.add(String.format("link %.0f,%.0f (water %.0f) -> %.0f,%.0f (water %.0f): %.0f blocks, bridge %s, %s, mid %.0f,%.0f",
                        A.x, A.z, A.waterLevel, rv.target.x, rv.target.z, rv.target.waterLevel, rv.len,
                        rv.bridged() ? String.format("%.0f..%.0f", rv.bridge0, rv.bridge1) : "none",
                        rv.waterfall() ? "waterfall" : "gentle", rv.px[mid], rv.pz[mid]));
            }
        if (a.landBridgeChance > 0) {
            java.util.Set<LandBridge> seen = new java.util.HashSet<>();
            for (int tx = Math.floorDiv(x0, RTILE); tx <= Math.floorDiv(x1, RTILE); tx++)
                for (int tz = Math.floorDiv(z0, RTILE); tz <= Math.floorDiv(z1, RTILE); tz++)
                    for (LandBridge lb : landBridgeTile(tx, tz)) if (seen.add(lb))
                        out.add(String.format("land bridge %.0f,%.0f -> %.0f,%.0f: %.0f blocks, deck y %.0f..%.0f, half-width %.1f",
                                lb.ax, lb.az, lb.ax + lb.len * lb.dx, lb.az + lb.len * lb.dz, lb.len, lb.hA, lb.hB, lb.w));
        }
        return out;
    }

    /**
     * A picker for any chunk, for tools outside the noise fill that sample heights (RoadWeaver's route planner): lake and
     * river water where the noise fill would put it. Each thread keeps the chunk pickers it used last (the noise fill's
     * own ArchPicker, which works its 256 columns out once and answers chunks with no lake or river near them at once),
     * since a sampler asks about one chunk's blocks in turn, moving to the next column every block.
     */
    public Aquifer.FluidPicker columnFluidPicker(Aquifer.FluidPicker fallback) {
        if (p.arch == null) return fallback;
        ThreadLocal<java.util.LinkedHashMap<Long, ArchPicker>> recent = ThreadLocal.withInitial(() ->
                new java.util.LinkedHashMap<>(16, 0.75f, true) {
                    @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long, ArchPicker> e) { return size() > 8; }
                });
        return (x, y, z) -> {
            long key = (long) (x >> 4) << 32 | ((z >> 4) & 0xFFFFFFFFL);
            java.util.LinkedHashMap<Long, ArchPicker> m = recent.get();
            ArchPicker ap = m.get(key);
            if (ap == null) m.put(key, ap = new ArchPicker(fallback));
            return ap.computeFluid(x, y, z);
        };
    }

    /** For tools: each river within reach of (x, z): its source, distance from its course, s along it and its level there. */
    public String riverAt(int x, int z) {
        StringBuilder sb = new StringBuilder();
        if (p.arch == null || p.arch.riverChance <= 0) return "";
        double[] at = new double[2];
        for (River rv : riverTile(Math.floorDiv(x, RTILE), Math.floorDiv(z, RTILE)))
            if (locate(rv, x, z, at)) sb.append(String.format("river from %.0f,%.0f -> %s: d=%.1f s=%.1f/%.1f hw=%.1f level=%d%n",
                    rv.px[0], rv.pz[0], rv.target == null ? "sky" : String.format("%.0f,%.0f", rv.target.x, rv.target.z), at[0], at[1], rv.len, width(rv, at[1]), levelAt(rv, at[1])));
        return sb.toString();
    }

    /** Whether (x, z) is within margin blocks of a river's channel (StructureMixin keeps structures off rivers). */
    public boolean nearRiver(int x, int z, double margin) {
        if (p.arch == null || p.arch.riverChance <= 0) return false;
        double[] at = new double[2];
        for (River rv : riverTile(Math.floorDiv(x, RTILE), Math.floorDiv(z, RTILE)))
            if (locate(rv, x, z, at) && at[0] < width(rv, at[1]) + margin && at[1] <= rv.len) return true;
        return false;
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
     * For each 4x4 column of the 16x16 chunk at (x0, z0), in (localX << 2 | localZ) order: the y ranges, in blocks, where
     * any island that can reach that column or the columns next to it can put land, as [lo, hi, lo, hi, ...] (empty if
     * none). The ranges come from each island's parameters at their noise maxima (Island verticalBound), not from
     * sampling, so a thin islet between sample points can't be missed. SkyBiomes keeps exact biomes inside them.
     */
    public double[][] skyEnvelopes(int x0, int z0) {
        Arch a = p.arch;
        double room = 2.0 * a.sharedRoom();
        int rx0 = x0 - 8, rz0 = z0 - 8, rx1 = x0 + 23, rz1 = z0 + 23;
        java.util.List<Island> cands = new java.util.ArrayList<>();
        for (int cx = (int) Math.floor(rx0 / a.cell) - a.search; cx <= (int) Math.floor(rx1 / a.cell) + a.search; cx++)
            for (int cz = (int) Math.floor(rz0 / a.cell) - a.search; cz <= (int) Math.floor(rz1 / a.cell) + a.search; cz++)
                addCandidate(cands, mainIsland(cx, cz), rx0, rz0, rx1, rz1, room);
        if (a.leySpacing > 0) {
            for (int cx = (int) Math.floor(rx0 / a.leyCell) - a.leySearch; cx <= (int) Math.floor(rx1 / a.leyCell) + a.leySearch; cx++)
                for (int cz = (int) Math.floor(rz0 / a.leyCell) - a.leySearch; cz <= (int) Math.floor(rz1 / a.leyCell) + a.leySearch; cz++)
                    addCandidate(cands, leyIsland(cx, cz), rx0, rz0, rx1, rz1, room);
        }
        if (a.pepperChance > 0) {
            for (int tx = Math.floorDiv(rx0, PEPPER_TILE); tx <= Math.floorDiv(rx1, PEPPER_TILE); tx++)
                for (int tz = Math.floorDiv(rz0, PEPPER_TILE); tz <= Math.floorDiv(rz1, PEPPER_TILE); tz++)
                    for (Island is : pepperTile(tx, tz)) if (!cands.contains(is)) addCandidate(cands, is, rx0, rz0, rx1, rz1, room);
        }
        java.util.List<River> rivers = new java.util.ArrayList<>();
        if (a.riverChance > 0) {
            for (int tx = Math.floorDiv(rx0, RTILE); tx <= Math.floorDiv(rx1, RTILE); tx++)
                for (int tz = Math.floorDiv(rz0, RTILE); tz <= Math.floorDiv(rz1, RTILE); tz++)
                    for (River rv : riverTile(tx, tz)) if (!rivers.contains(rv)) rivers.add(rv);
        }
        java.util.List<LandBridge> lands = new java.util.ArrayList<>();
        if (a.landBridgeChance > 0) {
            for (int tx = Math.floorDiv(rx0, RTILE); tx <= Math.floorDiv(rx1, RTILE); tx++)
                for (int tz = Math.floorDiv(rz0, RTILE); tz <= Math.floorDiv(rz1, RTILE); tz++)
                    for (LandBridge lb : landBridgeTile(tx, tz)) if (!lands.contains(lb)) lands.add(lb);
        }
        double[][] out = new double[16][];
        for (int lx = 0; lx < 4; lx++) {
            for (int lz = 0; lz < 4; lz++) {
                // This 4x4 column and its neighbours: biome lookups for a block can read the next column's sample.
                int qx0 = x0 + 4 * lx - 4, qz0 = z0 + 4 * lz - 4, qx1 = qx0 + 11, qz1 = qz0 + 11;
                double[] env = new double[0];
                for (Island is : cands) {
                    if (!reaches(is, qx0, qz0, qx1, qz1, room)) continue;
                    double[] b = verticalBound(is);
                    env = java.util.Arrays.copyOf(env, env.length + 2);
                    env[env.length - 2] = b[0];
                    env[env.length - 1] = b[1];
                }
                for (River rv : rivers) {
                    // Rivers lower land, and their berms and bridges add land near their water level.
                    if (!(rv.x0 < qx1 + 1 && rv.x1 > qx0 && rv.z0 < qz1 + 1 && rv.z1 > qz0)) continue;
                    env = java.util.Arrays.copyOf(env, env.length + 2);
                    env[env.length - 2] = rv.minLevel - rv.depth - 30.0;
                    env[env.length - 1] = rv.maxLevel + 8.0;
                }
                for (LandBridge lb : lands) {
                    if (!(lb.x0 < qx1 + 1 && lb.x1 > qx0 && lb.z0 < qz1 + 1 && lb.z1 > qz0)) continue;
                    env = java.util.Arrays.copyOf(env, env.length + 2);
                    env[env.length - 2] = lb.minY;
                    env[env.length - 1] = lb.maxY;
                }
                out[lx << 2 | lz] = env;
            }
        }
        return out;
    }

    private static void addCandidate(java.util.List<Island> out, Island is, int x0, int z0, int x1, int z1, double room) {
        if (is != NO_ISLAND && reaches(is, x0, z0, x1, z1, room)) out.add(is);
    }

    private static boolean reaches(Island is, int x0, int z0, int x1, int z1, double room) {
        double dx = Math.max(0.0, Math.max(x0 - is.x, is.x - x1)), dz = Math.max(0.0, Math.max(z0 - is.z, is.z - z1));
        double r = is.reach + room;
        return dx * dx + dz * dz < r * r;
    }

    /**
     * The lowest and highest y (blocks) island `is` can put land at, from addIsland with every noise at its maximum
     * (Arch.NOISE_MAX), plus 8 blocks. Wild islands get 64 more each way: their 3D detail noise is applied up to 60
     * blocks from a surface (compute's nearest >= -3, in units of B).
     */
    private double[] verticalBound(Island is) {
        Arch a = p.arch;
        double nm = Arch.NOISE_MAX, tier = 0.0;
        for (int k = 1; k < is.lobes.length / 4; k++) tier = Math.max(tier, Math.abs(is.lobes[4 * k + 3]));
        double top = is.base + tier + is.dome + is.hills * 1.45 * nm + is.peak * 1.2 + is.rim + is.cliff;
        if (is.bowlDepth > 0) {
            double lift = Math.max(0.0, (nm - (1.0 - a.islets)) / Math.max(0.05, a.islets));
            top = Math.max(top, is.waterLevel + 1.0 + 6.0 * lift + 2.0 * nm);
        }
        double depth = (is.edge + is.under * is.r) * (1.0 + a.undersideNoise * 1.5 * nm) + a.drips * Math.sqrt(is.r) * 3.0 * nm
                + a.peakRoot * is.peak;
        double bottom = is.base - tier - depth;
        // addIsland also caps the underside at 4 below the top, and a lake bed can sit bowlDepth under the water.
        if (is.bowlDepth > 0) bottom = Math.min(bottom, is.waterLevel - is.bowlDepth - 2.0 * nm - 8.0);
        double extra = 8.0 + (is.wild > 0 ? 64.0 : 0.0);
        return new double[]{bottom - extra, top + extra};
    }

    /**
     * The land in one column as (bottom, top) pairs in blocks, before 3D detail noise, with each lake's water level
     * counted as top; null if no island reaches the column. SkyBiomes keeps exact biomes within its margin of these.
     */
    public double[] landSpans(int x, int z) {
        Column c = new Column(this, x, z);
        fill(c, true, true);
        if (c.count == 0) return null;
        double[] out = new double[2 * c.count];
        for (int i = 0; i < c.count; i++) {
            out[2 * i] = c.bottom[i] / B;
            double top = c.top[i] / B;
            if (!Double.isNaN(c.basinLevel[i])) top = Math.max(top, c.basinLevel[i]);
            out[2 * i + 1] = top;
        }
        return out;
    }

    /** The seeded SkyTerrain in a world's noise router, or null if the world doesn't use one. */
    public static SkyTerrain of(net.minecraft.world.level.levelgen.RandomState state) {
        return BY_STATE.computeIfAbsent(state, s -> {
            SkyTerrain[] found = new SkyTerrain[1];
            s.router().fluidLevelFloodednessNoise().mapAll(df -> {
                if (df instanceof SkyTerrain t) found[0] = t;
                return df;
            });
            return java.util.Optional.ofNullable(found[0]);
        }).orElse(null);
    }

    private static final java.util.Map<net.minecraft.world.level.levelgen.RandomState, java.util.Optional<SkyTerrain>> BY_STATE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

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

        /**
         * True if the last computeFluid gave lake water at the lake's surface. The noise fill then marks the block for
         * the game to update when the chunk is finished, as vanilla does for aquifer water, so a lake surface that
         * ends up beside air (a road cut through the bank, a cave mouth) flows into it instead of standing as a wall.
         */
        public boolean lastSurface;

        @Override
        public Aquifer.FluidStatus computeFluid(int x, int y, int z) {
            lastSurface = false;
            if (lakes(x, z)) {
                int column = column(x, z);
                for (int i = 0; i < counts[column]; i++) {
                    double level = levels[column * CAP + i];
                    if (!Double.isNaN(level) && y < level && y > floors[column * CAP + i]) {
                        lastSurface = y + 1 >= level;
                        return water((int) level);
                    }
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
                    double level = c.basinLevel[i];
                    levels[column * CAP + i] = level;
                    // Only down to the lake bed (a little below), not to the underside: caves and overhangs under a
                    // lake must stay dry. The game's ground here is a blend of the 4-block cell's corners, never lower
                    // than the lowest corner, so the bed is taken as the lowest of this column and those corners.
                    double bed = c.top[i] / B;
                    if (!Double.isNaN(level)) bed = Math.min(bed, cornerBed(x, z, level));
                    // Where a corner of the cell doesn't hold water at this level (open sky at an island's edge or past a
                    // bridge's end, lower water past a step in a river, land below the water line), the blend sags the
                    // ground under the water by an unknown amount. Only the top block of water goes there: over air it
                    // spills off, where deeper water would stand against or hang over the air. And none goes where this
                    // column is itself bank above the water line: next to open sky the blend can take that bank away
                    // altogether, and water put in its place would hang in the air off the island's edge.
                    boolean low = !Double.isNaN(level) && lowCorner(x, z, level);
                    floors[column * CAP + i] = low ? (c.top[i] / B >= level ? level : level - 2.0) : bed - 3.0;
                }
                counts[column] = c.count;
            }
            return column;
        }

        /** The 5x5 cell corners over this chunk, each as {top, water level} pairs per layer; null until needed. */
        private final double[][] corners = new double[25][];

        /** The lowest surface among the corners of (x, z)'s cell that belong to the lake at `level`, in blocks. */
        private double cornerBed(int x, int z, double level) {
            int ci = (x & 15) >> 2, cj = (z & 15) >> 2;
            double bed = Double.POSITIVE_INFINITY;
            for (int a = 0; a <= 1; a++) {
                for (int b = 0; b <= 1; b++) {
                    double[] k = corner(x, z, ci + a, cj + b);
                    for (int i = 0; i < k.length; i += 2) if (k[i + 1] == level) bed = Math.min(bed, k[i]);
                }
            }
            return bed;
        }

        /**
         * Whether a corner of (x, z)'s 4-block cell fails to hold water at `level`: it holds it if it has water at that
         * level, or land up to the water line.
         */
        private boolean lowCorner(int x, int z, double level) {
            int ci = (x & 15) >> 2, cj = (z & 15) >> 2;
            for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) {
                double[] k = corner(x, z, ci + a, cj + b);
                boolean holds = false;
                for (int i = 0; i < k.length && !holds; i += 2) holds = k[i + 1] == level || k[i] >= level;
                if (!holds) return true;
            }
            return false;
        }

        private double[] corner(int x, int z, int ci, int cj) {
            double[] k = corners[ci * 5 + cj];
            if (k == null) {
                Column c = new Column(SkyTerrain.this, (x & ~15) + 4 * ci, (z & ~15) + 4 * cj);
                fill(c, true, true);
                k = new double[2 * c.count];
                for (int i = 0; i < c.count; i++) {
                    k[2 * i] = c.top[i] / B;
                    k[2 * i + 1] = c.basinLevel[i];
                }
                corners[ci * 5 + cj] = k;
            }
            return k;
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
        // Rivers cut channels through other islands' land and cross open sky on bridges, outside their own island's reach.
        if (a.riverChance > 0) {
            for (River rv : riverTile(Math.floorDiv(x0, RTILE), Math.floorDiv(z0, RTILE)))
                if (rv.x0 < x1 + 1 && rv.x1 > x0 && rv.z0 < z1 + 1 && rv.z1 > z0) return true;
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
        final double[] scratch = new double[3];
        double maxK, wild, plateau, channel = Double.NaN;
        /** 0 to 1: how close this column is to a lake's water (1 within its bank); crags fade out by it on every layer. */
        double lakeNear;
        /** Islands only, without rivers and bridges: what a river's course is planned against. */
        boolean bare;
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
        final double lakeBank, lakeShore, lakeCoastGap, raisedBias, raisedGain, raisedWavelength;
        final double riverChance, riverHalfWidth, riverDepth, riverStepMin, riverStepMax, riverBankSlope, riverMeander,
                riverMeanderWavelength, riverMaxCut;
        final double chainChance, chainDistrict, chainMaxLength, chainMaxGap, chainMaxDrop;
        final double landBridgeChance, landBridgeSecond, landBridgeMaxGap;
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
            JsonObject lk = o.has("lakeShore") ? o.getAsJsonObject("lakeShore") : null;
            lakeBank = Params.num(lk, "bank", 12);
            lakeShore = Params.num(lk, "shore", 4);
            lakeCoastGap = Params.num(lk, "coastGap", 8);
            raisedBias = Params.num(lk, "raisedBias", 0.0);
            raisedGain = Params.num(lk, "raisedGain", 2.5);
            raisedWavelength = Params.num(lk, "raisedWavelength", 1.2);
            // Lake outflow rivers; off unless the settings have a "rivers" object.
            JsonObject rv = o.has("rivers") ? o.getAsJsonObject("rivers") : null;
            riverChance = rv == null ? 0 : Params.num(rv, "chance", 1.0);
            riverHalfWidth = Params.num(rv, "halfWidth", 4.0);
            riverDepth = Params.num(rv, "depth", 2.5);
            riverStepMin = Params.num(rv, "stepMin", 3);
            riverStepMax = Params.num(rv, "stepMax", 6);
            riverBankSlope = Params.num(rv, "bankSlope", 1.5);
            riverMeander = Params.num(rv, "meander", 1.0);
            riverMeanderWavelength = Params.num(rv, "meanderWavelength", 60);
            riverMaxCut = Params.num(rv, "maxCut", 30);
            // Lake chains: links between neighbouring lake islands; off unless the settings have a "chains" object (and rivers).
            JsonObject chn = o.has("chains") ? o.getAsJsonObject("chains") : null;
            chainChance = chn == null || rv == null ? 0 : Params.num(chn, "chance", 0.8);
            chainDistrict = Params.num(chn, "district", 0.4);
            chainMaxLength = Params.num(chn, "maxLength", 420);
            chainMaxGap = Params.num(chn, "maxGap", 160);
            chainMaxDrop = Params.num(chn, "maxDrop", 60);
            // Land bridges between neighbouring islands; off unless the settings have a "landBridges" object.
            JsonObject lbs = o.has("landBridges") ? o.getAsJsonObject("landBridges") : null;
            landBridgeChance = lbs == null ? 0 : Params.num(lbs, "chance", 0.8);
            landBridgeSecond = Params.num(lbs, "second", 0.3);
            landBridgeMaxGap = Params.num(lbs, "maxGap", 180);
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
