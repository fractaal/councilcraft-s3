package skyislands;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.slf4j.Logger;

/**
 * Where a chunk's structure blending (Beardifier) can add anything: a list of boxes, outside all of which every
 * contribution to Beardifier.compute is exactly 0.0 and none of the mods' side effects run. In a sky-island world a
 * structure's blending reaches a few dozen blocks around it, while the chunk is hundreds of blocks tall, so most of the
 * chunk's cells can skip it.
 *
 * What adds to Beardifier.compute in this pack, and how far each reaches (each box here is a little larger):
 * - Vanilla pieces (Beardifier.Rigid, which Iron's Spellbooks, Golem Dungeons, Lithostitched and NeoForge also add to):
 *   within 12 blocks of the piece's box sideways, and within 12 of the box or of its ground level (minY + delta) in y.
 * - Vanilla jigsaw junctions: within 12 blocks of the junction in each axis.
 * - YUNG's API 5.1.9 and Moog's Structure Lib 3.4.2 enhanced pieces and junctions: within the adaptation's kernel size of
 *   the piece's box after its bottom offset, padding and (Moog's) band, or of the junction's offset ground point.
 * - RoadWeaver 2.3.2's density stamp: only the chunk's own columns, between the lowest fill depth and the highest carve
 *   height of its columns.
 * Any other field value, or a list entry of another type, makes the reach unknown, and the chunk keeps full blending.
 */
public final class BlendReach {
    private static final Logger LOG = LogUtils.getLogger();
    /** Blending adds nothing anywhere in the chunk. */
    public static final int[] NONE = new int[0];

    private static final int VANILLA = 24;
    private static final String YUNG = "com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.beardifier.";
    private static final String MOOG = "com.finndog.moogs_structures.world.structures.terrainadaptation.beardifier.";
    private static final String ROAD_PLAN = "net.shiroha233.roadweaver.worldgen.road.RoadChunkPlan";

    /** How many entries of each kind chunks' reaches were built from, for the lab's check of which kinds it covered. */
    public static final Map<String, java.util.concurrent.atomic.AtomicLong> KINDS = new ConcurrentHashMap<>();

    private int[] boxes = new int[6 * 8];
    private int n;

    private BlendReach() {}

    /**
     * The boxes, six ints each (minX, minY, minZ, maxX, maxY, maxZ), that touch the chunk's columns: NONE if blending is
     * zero everywhere in the chunk, or null if it can't be worked out.
     */
    public static int[] of(DensityFunctions.BeardifierOrMarker b, ChunkPos pos) {
        if (b == FastGen.ZERO_BEARD) return NONE;
        if (!(b instanceof Beardifier)) return null;
        BlendReach r = new BlendReach();
        try {
            for (Field f : fields()) {
                Object v = f.get(b);
                if (v == null || v instanceof NoiseChunk) continue;
                if (!r.addValue(v)) {
                    report(f.getName() + " (" + v.getClass().getName() + ")");
                    return null;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            report("read failed: " + e);
            return null;
        }
        return r.inChunk(pos);
    }

    /** True if the box from (x0, y0, z0) to (x1, y1, z1) touches one of the boxes. */
    public static boolean touches(int[] reach, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int i = 0; i < reach.length; i += 6) {
            if (x0 <= reach[i + 3] && x1 >= reach[i] && y0 <= reach[i + 4] && y1 >= reach[i + 1] && z0 <= reach[i + 5] && z1 >= reach[i + 2]) return true;
        }
        return false;
    }

    private boolean addValue(Object v) throws ReflectiveOperationException {
        if (v instanceof ListIterator<?> it) return addAll(it);
        if (v instanceof Collection<?> c) {
            for (Object e : c) if (!addEntry(e)) return false;
            return true;
        }
        if (v instanceof Iterator<?> it) return !it.hasNext();
        if (v instanceof Map<?, ?> m) return m.isEmpty();
        if (v instanceof Object[] a) return a.length == 0;
        if (ROAD_PLAN.equals(v.getClass().getName())) return addRoad(v);
        return false;
    }

    /** Reads every entry of a shared list iterator (vanilla's and Moog's), then puts its cursor back where it was. */
    private boolean addAll(ListIterator<?> it) throws ReflectiveOperationException {
        int start = 0, end = 0;
        while (it.hasPrevious()) { it.previous(); start++; }
        boolean ok = true;
        try {
            while (it.hasNext()) {
                Object e = it.next();
                end++;
                if (ok && !addEntry(e)) ok = false;
            }
        } finally {
            for (int k = end; k > start; k--) it.previous();
        }
        return ok;
    }

    private boolean addEntry(Object e) throws ReflectiveOperationException {
        if (e instanceof Beardifier.Rigid rigid) {
            count("vanilla piece " + rigid.terrainAdjustment());
            if (rigid.terrainAdjustment() == TerrainAdjustment.NONE) return true;
            BoundingBox b = rigid.box();
            int ground = b.minY() + rigid.groundLevelDelta();
            add(b.minX() - VANILLA, Math.min(b.minY(), ground) - VANILLA, b.minZ() - VANILLA,
                    b.maxX() + VANILLA, Math.max(b.maxY(), ground) + VANILLA, b.maxZ() + VANILLA);
            return true;
        }
        if (e instanceof JigsawJunction j) {
            count("vanilla junction");
            junction(j, 0, VANILLA);
            return true;
        }
        String type = e.getClass().getName();
        boolean moog = type.startsWith(MOOG);
        if (!moog && !type.startsWith(YUNG)) return false;
        String name = type.substring((moog ? MOOG : YUNG).length());
        count((moog ? "moog " : "yung ") + name);
        if (name.equals("EnhancedBeardifierRigid")) {
            BoundingBox b = (BoundingBox) call(e, "pieceBoundingBox");
            Object a = call(e, "pieceTerrainAdaptation");
            int k = Math.max(0, (Integer) call(a, "getKernelSize"));
            int offset = (int) (double) (Double) call(a, "getBottomOffset");
            Object pad = call(a, "getPadding");
            int side = Math.max(0, Math.max((Integer) call(pad, "x"), (Integer) call(pad, "z")));
            int lo = b.minY() + Math.min(0, offset) - Math.max(0, (Integer) call(pad, "bottom"));
            int hi = b.maxY() + Math.max(0, offset) + Math.max(0, (Integer) call(pad, "top"));
            if (moog && call(a, "getBand") instanceof Optional<?> band && band.isPresent()) {
                int floor = b.minY() + offset, bottom = floor + (Integer) call(band.get(), "bottom"), top = floor + (Integer) call(band.get(), "top");
                lo = Math.min(lo, Math.min(bottom, top));
                hi = Math.max(hi, Math.max(bottom, top));
            }
            add(b.minX() - side - k, lo - k, b.minZ() - side - k, b.maxX() + side + k, hi + k, b.maxZ() + side + k);
            return true;
        }
        if (name.equals("EnhancedJigsawJunction")) {
            Object a = call(e, "pieceTerrainAdaptation");
            junction((JigsawJunction) call(e, "jigsawJunction"), (int) (double) (Double) call(a, "getBottomOffset"),
                    Math.max(0, (Integer) call(a, "getKernelSize")));
            return true;
        }
        return false;
    }

    private void junction(JigsawJunction j, int offset, int k) {
        int y = j.getSourceGroundY();
        add(j.getSourceX() - k, y + Math.min(0, offset) - k, j.getSourceZ() - k, j.getSourceX() + k, y + Math.max(0, offset) + k, j.getSourceZ() + k);
    }

    /**
     * RoadWeaver's road density (RoadDensityStamp.density): in a column of the stamp's chunk, fill below fillTargetY for
     * fillDepth.length blocks, and carve above carveTargetY for less than carveDepth.length blocks.
     */
    private boolean addRoad(Object plan) throws ReflectiveOperationException {
        Object stamp = call(plan, "densityStamp");
        if (stamp == null || (Boolean) call(stamp, "isEmpty")) return true;
        count("road stamp");
        ChunkPos pos = (ChunkPos) call(stamp, "chunkPos");
        int[] fill = (int[]) field(stamp, "fillTargetY"), carve = (int[]) field(stamp, "carveTargetY");
        int fillDepth = ((float[]) field(stamp, "fillDepth")).length, carveDepth = ((float[]) field(stamp, "carveDepth")).length;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < fill.length; i++) {
            if (fill[i] != Integer.MIN_VALUE) { lo = Math.min(lo, fill[i] - fillDepth); hi = Math.max(hi, fill[i]); }
        }
        for (int i = 0; i < carve.length; i++) {
            if (carve[i] != Integer.MIN_VALUE) { lo = Math.min(lo, carve[i]); hi = Math.max(hi, carve[i] + carveDepth); }
        }
        if (lo <= hi) add(pos.getMinBlockX() - 1, lo - 1, pos.getMinBlockZ() - 1, pos.getMaxBlockX() + 1, hi + 1, pos.getMaxBlockZ() + 1);
        return true;
    }

    private static void count(String kind) {
        KINDS.computeIfAbsent(kind, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
    }

    private void add(int x0, int y0, int z0, int x1, int y1, int z1) {
        if (n + 6 > boxes.length) boxes = Arrays.copyOf(boxes, boxes.length * 2);
        boxes[n++] = x0; boxes[n++] = y0; boxes[n++] = z0;
        boxes[n++] = x1; boxes[n++] = y1; boxes[n++] = z1;
    }

    private int[] inChunk(ChunkPos pos) {
        int x0 = pos.getMinBlockX(), z0 = pos.getMinBlockZ(), x1 = pos.getMaxBlockX(), z1 = pos.getMaxBlockZ();
        int[] out = new int[n];
        int m = 0;
        for (int i = 0; i < n; i += 6) {
            if (x0 <= boxes[i + 3] && x1 >= boxes[i] && z0 <= boxes[i + 5] && z1 >= boxes[i + 2]) {
                System.arraycopy(boxes, i, out, m, 6);
                m += 6;
            }
        }
        return m == 0 ? NONE : Arrays.copyOf(out, m);
    }

    // ---- Reflection ------------------------------------------------------------------------------------------------

    private static volatile Field[] fields;
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> REPORTED = new ConcurrentHashMap<>();

    private static Object call(Object o, String name) throws ReflectiveOperationException {
        String key = o.getClass().getName() + '#' + name;
        Method m = METHODS.get(key);
        if (m == null) {
            m = o.getClass().getMethod(name);
            m.setAccessible(true);
            METHODS.put(key, m);
        }
        return m.invoke(o);
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        String key = o.getClass().getName() + '#' + name;
        Field f = FIELDS.get(key);
        if (f == null) {
            f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            FIELDS.put(key, f);
        }
        return f.get(o);
    }

    private static void report(String what) {
        if (REPORTED.putIfAbsent(what, true) == null) {
            LOG.info("[skyislands] Structure blending reach unknown because of {}, so chunks with it keep full blending", what);
        }
    }

    /** Every instance field of the Beardifier, including the ones mods add. */
    private static synchronized Field[] fields() {
        Field[] fs = fields;
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
        return fields = out.toArray(new Field[0]);
    }
}
