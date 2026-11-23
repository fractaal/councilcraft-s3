package gamebridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Looking at the world: where a block is, and a top-down picture of an area.
 *
 * Both read loaded chunks only. The bridge never loads or generates a chunk to answer a question, because that costs
 * the server thread time it can't bound; each reply says how much of the asked area was loaded and scanned. Sable's
 * airships live outside the normal chunks and are not seen by either.
 */
final class Sight {
    private Sight() {}

    private static final int MAX_RADIUS = 256;

    /**
     * The centre of a request: "target" (a player or entity) or "near": [x, z] or [x, y, z], with "dimension". y (the
     * feet) and eye are null when no height was given. who and at are the target entity's name and exact position,
     * null for "near"; player says the target is a player, who is marked like any other.
     */
    private record Centre(ServerLevel level, int x, int z, Integer y, Integer eye, String who, double[] at, boolean player) {}

    private static Centre centre(JsonObject p) {
        if (p.has("target")) {
            var e = Methods.one(p);
            return new Centre((ServerLevel) e.level(), e.getBlockX(), e.getBlockZ(), e.getBlockY(), Mth.floor(e.getEyeY()), e.getName().getString(),
                    new double[] {e.getX(), e.getY(), e.getZ()}, e instanceof ServerPlayer);
        }
        if (!p.has("near")) throw new Bridge.Refusal("bad_params", "This needs \"target\" (a player or entity to centre on) or \"near\": [x, z] or [x, y, z].");
        JsonArray n = p.getAsJsonArray("near");
        Integer y = n.size() >= 3 ? Mth.floor(n.get(1).getAsDouble()) : null;
        return new Centre(Methods.level(p), Mth.floor(n.get(0).getAsDouble()), Mth.floor(n.get(n.size() - 1).getAsDouble()), y, y, null, null, false);
    }

    /**
     * The highest y a picture keeps: "max_y", or with "cutaway" the target's eye level, so that canopy, cave roof or
     * ceiling above their head doesn't hide what is around them. Null when nothing is cut off the top.
     */
    private static Integer cutTop(JsonObject p, Centre c) {
        boolean cutaway = p.has("cutaway") && p.get("cutaway").getAsBoolean();
        if (!cutaway) return p.has("max_y") ? p.get("max_y").getAsInt() : null;
        if (p.has("max_y")) throw new Bridge.Refusal("bad_params", "Give \"cutaway\" or \"max_y\", not both: cutaway sets the top of the picture to the target's eye level.");
        if (c.eye == null) throw new Bridge.Refusal("bad_params", "\"cutaway\" cuts at the target's eye level, so it needs a target, or \"near\": [x, y, z] with a height.");
        return c.eye;
    }

    private static String cutReason(JsonObject p, Centre c, int top) {
        if (!(p.has("cutaway") && p.get("cutaway").getAsBoolean())) return "max_y";
        return (c.who != null ? c.who + "'s" : "the given") + " eye level, y " + top;
    }

    /**
     * Pictures are sized for a model to look at. Claude scales an image down to fit 1568 px on its long side and about
     * 1.15 megapixels, so a bigger picture carries no more detail and a smaller one throws detail away. Returns the
     * largest whole scale at which a picture w by h units fits, between 1 and max.
     */
    private static int fit(int w, int h, int max) {
        double t = Math.min(1568.0 / Math.max(w, h), Math.sqrt(1_150_000.0 / ((double) w * h)));
        return Math.max(1, Math.min(max, (int) t));
    }

    private static final int MAX_SCALE = 16;

    private static int askedScale(JsonObject p) {
        if (!p.has("scale")) return 0;
        int s = p.get("scale").getAsInt();
        if (s < 1 || s > MAX_SCALE) throw new Bridge.Refusal("bad_params", "\"scale\" must be 1 to " + MAX_SCALE + ". Leave it out to get the largest picture a model can see in full.");
        return s;
    }

    /** Diagonal stripes, every third line dark, marking faces where a picture cuts through solid blocks. */
    private static boolean stripe(int x, int y, int unit) {
        return Math.floorMod(x - y, 3 * unit) < unit;
    }

    private static int blend(int under, int over, double alpha) {
        int r = (int) ((under >> 16 & 255) * (1 - alpha) + (over >> 16 & 255) * alpha);
        int g = (int) ((under >> 8 & 255) * (1 - alpha) + (over >> 8 & 255) * alpha);
        int b = (int) ((under & 255) * (1 - alpha) + (over & 255) * alpha);
        return r << 16 | g << 8 | b;
    }

    /** How strongly water tints what is under it. Water is drawn see-through so builds and floors under it show. */
    private static final double WATER_ALPHA = 0.5;

    private static int radius(JsonObject p, int fallback) {
        int r = p.has("radius") ? p.get("radius").getAsInt() : fallback;
        if (r < 1 || r > MAX_RADIUS) throw new Bridge.Refusal("bad_params", "\"radius\" must be between 1 and " + MAX_RADIUS + " blocks.");
        return r;
    }

    // ---- find a block ----

    /**
     * Where "block" (an id) is within "radius" blocks (default 96) of the centre, as clusters: blocks within 24 of
     * each other count as one place. Chunks are scanned nearest first for at most "budget_ms" (default 150, up to
     * 1000) of server-thread time.
     */
    static JsonElement find(JsonObject p) {
        if (!p.has("block")) throw new Bridge.Refusal("bad_params", "find_blocks needs \"block\": a block id such as minecraft:budding_amethyst.");
        String id = p.get("block").getAsString();
        Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id.contains(":") ? id : "minecraft:" + id))
                .orElseThrow(() -> new Bridge.Refusal("unknown_block", "There is no block '" + id + "'. Ids look like minecraft:budding_amethyst or create:track."));
        Centre c = centre(p);
        int r = radius(p, 96);
        long budget = Math.max(10, Math.min(1000, p.has("budget_ms") ? p.get("budget_ms").getAsLong() : 150)) * 1_000_000L;

        List<int[]> chunks = new ArrayList<>();
        for (int cx = (c.x - r) >> 4; cx <= (c.x + r) >> 4; cx++)
            for (int cz = (c.z - r) >> 4; cz <= (c.z + r) >> 4; cz++) chunks.add(new int[] {cx, cz});
        chunks.sort(Comparator.comparingLong(ch -> sq(ch[0] * 16 + 8 - c.x) + sq(ch[1] * 16 + 8 - c.z)));

        List<int[]> hits = new ArrayList<>();
        int scanned = 0, notLoaded = 0, unreached = 0;
        double coveredTo = -1;
        long deadline = System.nanoTime() + budget;
        for (int i = 0; i < chunks.size(); i++) {
            int[] ch = chunks.get(i);
            if (System.nanoTime() > deadline) {
                unreached = chunks.size() - i;
                coveredTo = Math.sqrt(sq(ch[0] * 16 + 8 - c.x) + sq(ch[1] * 16 + 8 - c.z)) - 12;
                break;
            }
            LevelChunk chunk = c.level.getChunkSource().getChunkNow(ch[0], ch[1]);
            if (chunk == null) { notLoaded++; continue; }
            scanned++;
            LevelChunkSection[] sections = chunk.getSections();
            for (int s = 0; s < sections.length; s++) {
                LevelChunkSection section = sections[s];
                if (section == null || section.hasOnlyAir() || !section.maybeHas(state -> state.is(block))) continue;
                int baseY = chunk.getSectionYFromSectionIndex(s) << 4;
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    if (!section.getBlockState(x, y, z).is(block)) continue;
                    int wx = ch[0] * 16 + x, wz = ch[1] * 16 + z;
                    if (Math.abs(wx - c.x) <= r && Math.abs(wz - c.z) <= r) hits.add(new int[] {wx, baseY + y, wz});
                }
            }
        }

        List<List<int[]>> clusters = new ArrayList<>();
        for (int[] h : hits) {
            List<int[]> home = null;
            for (List<int[]> cl : clusters) {
                int[] f = cl.get(0);
                if (Math.abs(f[0] - h[0]) < 24 && Math.abs(f[1] - h[1]) < 24 && Math.abs(f[2] - h[2]) < 24) { home = cl; break; }
            }
            if (home == null) clusters.add(home = new ArrayList<>());
            home.add(h);
        }
        JsonArray out = new JsonArray();
        clusters.stream().map(cl -> {
            double x = 0, y = 0, z = 0;
            for (int[] h : cl) { x += h[0]; y += h[1]; z += h[2]; }
            int n = cl.size();
            JsonObject o = new JsonObject();
            JsonArray pos = new JsonArray();
            pos.add(Math.round(x / n)); pos.add(Math.round(y / n)); pos.add(Math.round(z / n));
            o.add("pos", pos);
            o.addProperty("blocks", n);
            o.addProperty("distance", Math.round(Math.sqrt(sq(x / n - c.x) + sq(z / n - c.z))));
            return o;
        }).sorted(Comparator.comparingLong(o -> o.get("distance").getAsLong())).limit(40).forEach(out::add);

        JsonObject o = new JsonObject();
        o.addProperty("block", BuiltInRegistries.BLOCK.getKey(block).toString());
        o.addProperty("dimension", c.level.dimension().location().toString());
        o.add("centre", xz(c.x, c.z));
        o.addProperty("radius", r);
        o.addProperty("blocks_found", hits.size());
        o.addProperty("places", clusters.size());
        o.addProperty("chunks_in_range", chunks.size());
        o.addProperty("chunks_scanned", scanned);
        o.addProperty("chunks_not_loaded", notLoaded);
        o.addProperty("chunks_not_reached", unreached);
        o.addProperty("complete", notLoaded == 0 && unreached == 0);
        List<String> notes = new ArrayList<>();
        if (unreached > 0) notes.add("The time budget ran out with " + unreached + " chunks unscanned. Nearest chunks go first, so everything within about "
                + Math.max(0, Math.round(coveredTo)) + " blocks was covered; raise \"budget_ms\" (up to 1000) or lower \"radius\" for the rest.");
        if (notLoaded > 0) notes.add(notLoaded + " chunks in range are not loaded and were not scanned, so this can miss blocks there. The bridge does not load chunks; they load when a player is near.");
        if (clusters.size() > 40) notes.add("Only the nearest 40 of " + clusters.size() + " places are listed.");
        if (!notes.isEmpty()) o.addProperty("note", String.join(" ", notes));
        o.add("found", out);
        return o;
    }

    // ---- top-down picture ----

    private static final int NOT_LOADED = 0x3A0A3A, VOID = 0x05070F, MARKER = 0xFF2020, TARGET_MARKER = 0xFFD800;

    /** How far down a column is searched, past blocks with no map colour, air under a cut and water, for something to colour. */
    private static final int SCAN = 32;

    /** One colour and height per column, read on the server thread; the picture is made from it off that thread. */
    private static final class Plan {
        String dimension, cutWhy;
        int size, x0, z0, cx, cz, floor;
        Integer cut;
        int[] colour, height;        // colour 0: nothing found (void); one extra row to the north in height, for slope shading
        boolean[] loaded, sliced;    // loaded has the extra row too; sliced: the column was cut through a solid block
        boolean water;
        List<double[]> players = new ArrayList<>();
        List<String> names = new ArrayList<>();
    }

    /**
     * A top-down picture of the area within "radius" blocks (default 96) of the centre, north up, coloured with the
     * same map colours the in-game map item uses and shaded by slope. Each block is a square of "scale" pixels, by
     * default the largest that a model sees in full. "max_y", or "cutaway" (the target's eye level), shows each column
     * from that height down: a plan of a cave, a building's floor or the ground under trees. Water is see-through.
     * Returned as a base64 PNG with what is needed to read it: the world position of the top-left pixel, the colours
     * that mean "chunk not loaded" and "nothing there (void)", the height range, and where players are.
     */
    static void map(JsonObject p, JsonObject reply) {
        Plan[] holder = new Plan[1];
        JsonObject inner = new JsonObject();
        if (!Bridge.onServerThread(inner, 8000, () -> { holder[0] = plan(p); return com.google.gson.JsonNull.INSTANCE; })) {
            inner.entrySet().forEach(e -> reply.add(e.getKey(), e.getValue()));
            return;
        }
        try {
            Bridge.ok(reply, drawPlan(holder[0], askedScale(p)));
        } catch (IOException | RuntimeException e) {
            Bridge.fail(reply, "failed", "The blocks were read but the picture could not be drawn: " + e);
        }
    }

    private static Plan plan(JsonObject p) {
        Centre c = centre(p);
        Plan m = new Plan();
        int r = radius(p, 96);
        askedScale(p);
        m.cut = cutTop(p, c);
        if (m.cut != null) m.cutWhy = cutReason(p, c, m.cut);
        m.dimension = c.level.dimension().location().toString();
        m.size = 2 * r + 1; m.x0 = c.x - r; m.z0 = c.z - r; m.cx = c.x; m.cz = c.z;
        int size = m.size;
        m.floor = c.level.getMinBuildHeight();
        m.height = new int[size * (size + 1)];
        m.colour = new int[size * size];
        m.loaded = new boolean[size * (size + 1)];
        m.sliced = new boolean[size * size];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LevelChunk chunk = null;
        int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;
        for (int row = -1; row < size; row++) {
            for (int col = 0; col < size; col++) {
                int wx = m.x0 + col, wz = m.z0 + row, i = (row + 1) * size + col;
                if (wx >> 4 != chunkX || wz >> 4 != chunkZ) {
                    chunkX = wx >> 4; chunkZ = wz >> 4;
                    chunk = c.level.getChunkSource().getChunkNow(chunkX, chunkZ);
                }
                if (chunk == null) { m.height[i] = m.floor; continue; }
                m.loaded[i] = true;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);
                boolean below = m.cut != null && y > m.cut;
                if (below) y = m.cut;
                int rgb = 0, water = 0, waterY = 0;
                boolean sliced = false;
                for (int down = 0; down < SCAN && y >= m.floor; ) {
                    if (chunk.getSection(chunk.getSectionIndex(y)).hasOnlyAir()) {   // open sky under an island: skip the section
                        down += (y & 15) + 1; y -= (y & 15) + 1;
                        continue;
                    }
                    BlockState state = chunk.getBlockState(pos.set(wx, y, wz));
                    MapColor mc = state.getMapColor(c.level, pos);
                    if (mc != MapColor.NONE && mc != MapColor.WATER) {
                        rgb = mc.col;
                        sliced = below && down == 0;
                        break;
                    }
                    if (mc == MapColor.WATER && water == 0) { water = mc.col; waterY = y; }
                    down++; y--;
                }
                if (water != 0) {
                    m.water = true;
                    if (rgb == 0) { rgb = water; y = waterY; }
                    else rgb = blend(rgb, water, WATER_ALPHA);
                }
                m.height[i] = Math.max(y, m.floor);
                if (row >= 0) { m.colour[row * size + col] = rgb; m.sliced[row * size + col] = sliced; }
            }
        }
        for (ServerPlayer player : c.level.players()) {
            int col = player.getBlockX() - m.x0, row = player.getBlockZ() - m.z0;
            if (col < 0 || row < 0 || col >= size || row >= size) continue;
            m.players.add(new double[] {player.getX(), player.getY(), player.getZ()});
            m.names.add(player.getGameProfile().getName());
        }
        return m;
    }

    private static JsonObject drawPlan(Plan m, int asked) throws IOException {
        int size = m.size, s = asked > 0 ? asked : fit(size, size, MAX_SCALE), w = size * s;
        int[] rgb = new int[size * size];
        int missing = 0, voids = 0, sliced = 0, lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                int i = (row + 1) * size + col, north = row * size + col, k = row * size + col;
                if (!m.loaded[i]) { rgb[k] = NOT_LOADED; missing++; continue; }
                if (m.colour[k] == 0) { rgb[k] = VOID; voids++; continue; }
                int h = m.height[i];
                lowest = Math.min(lowest, h); highest = Math.max(highest, h);
                if (m.sliced[k]) sliced++;
                double shade = (!m.loaded[north] || h == m.height[north] ? 220 : h > m.height[north] ? 255 : 180) / 255.0;
                rgb[k] = blend(0, m.colour[k], shade);
            }
        }
        // Player markers: a red square five blocks across with a white border, over everything.
        boolean[] marker = new boolean[size * size];
        JsonArray players = new JsonArray();
        for (int n = 0; n < m.players.size(); n++) {
            double[] q = m.players.get(n);
            int col = Mth.floor(q[0]) - m.x0, row = Mth.floor(q[2]) - m.z0;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                int px = col + dx, py = row + dy;
                if (px < 0 || py < 0 || px >= size || py >= size) continue;
                rgb[py * size + px] = Math.abs(dx) == 2 || Math.abs(dy) == 2 ? 0xFFFFFF : MARKER;
                marker[py * size + px] = true;
            }
            JsonObject pl = new JsonObject();
            pl.addProperty("name", m.names.get(n));
            pl.add("pos", Methods.pos(q[0], q[1], q[2]));
            JsonArray px = new JsonArray(); px.add(col * s + s / 2); px.add(row * s + s / 2);
            pl.add("pixel", px);
            players.add(pl);
        }
        int unit = Math.max(1, s / 3);
        byte[] big = new byte[w * w * 3];
        for (int y = 0; y < w; y++) for (int x = 0; x < w; x++) {
            int k = (y / s) * size + x / s, c = rgb[k];
            if (m.sliced[k] && !marker[k] && stripe(x, y, unit)) c = blend(0, c, 0.45);
            int o = (y * w + x) * 3;
            big[o] = (byte) (c >> 16); big[o + 1] = (byte) (c >> 8); big[o + 2] = (byte) c;
        }

        JsonObject o = new JsonObject();
        o.addProperty("dimension", m.dimension);
        o.addProperty("width", w);
        o.addProperty("height", w);
        o.addProperty("pixels_per_block", s);
        o.add("top_left_block", xz(m.x0, m.z0));
        o.add("centre_block", xz(m.cx, m.cz));
        o.addProperty("north", "up");
        o.addProperty("colour_not_loaded", "#3A0A3A (dark purple): the chunk is not loaded, so nothing is known about it");
        o.addProperty("colour_void", m.cut == null ? "#05070F (near black): loaded, and there is no block in that column"
                : "#05070F (near black): loaded, and no block within " + SCAN + " below the cut, or none at all");
        o.addProperty("not_loaded_percent", Math.round(1000.0 * missing / (size * size)) / 10.0);
        o.addProperty("void_percent", Math.round(1000.0 * voids / (size * size)) / 10.0);
        if (highest >= lowest) { o.addProperty("lowest_surface_y", lowest); o.addProperty("highest_surface_y", highest); }
        o.addProperty("shading", "a column higher than its north neighbour is brighter, lower is darker");
        if (m.cut != null) {
            o.addProperty("cut_at_y", m.cut);
            o.addProperty("cut", "Each column is shown from y " + m.cut + " (" + m.cutWhy + ") down, as if everything above were lifted off: open floors, the ground under trees and cave floors show. "
                    + "Squares striped with dark diagonal lines (" + Math.round(1000.0 * sliced / (size * size)) / 10.0 + "% of the area) are solid at that height, cut through, not a floor.");
        }
        if (m.water) o.addProperty("water", "Water is see-through: what is under it shows, tinted blue. Water deeper than " + SCAN + " blocks shows as plain water.");
        o.addProperty("player_marker", "red square with a white border, five blocks across");
        o.add("players_in_view", players);
        o.addProperty("png_base64", Base64.getEncoder().encodeToString(png(big, w, w)));
        return o;
    }

    // ---- isometric picture ----

    /**
     * The blocks of an area as map colours, already turned to the view's direction. a runs towards the picture's
     * lower right, b towards its lower left, and both are 0 to n-1 inside the area. A cell is 0 when there is
     * nothing to draw, else its colour, with WATER set for see-through water.
     */
    private static final class Volume {
        String dimension, cutWhy;
        int n, p, x0, z0, yMin, h, turns, chunksInRange, chunksNotLoaded, chunksNotReached, blocksLo, blocksHi;
        boolean cutTop;
        int[] cells;            // (n + 2)² × (h + 1): the area, a ring one block wide around it and one layer above, which are only read to tell a cut face from a real one
        boolean[] columnKnown;  // (n + 2)²: whether each column of the area and its ring is in a chunk that was read
        List<Mark> marks = new ArrayList<>();

        // Turns world offsets, -1 to n, into the view's axes in the ring's frame, 0 to p - 1.
        int pa(int dx, int dz) { return switch (turns) { case 1 -> dz + 1; case 2 -> p - 2 - dx; case 3 -> p - 2 - dz; default -> dx + 1; }; }
        int pb(int dx, int dz) { return switch (turns) { case 1 -> p - 2 - dx; case 2 -> p - 2 - dz; case 3 -> dx + 1; default -> dz + 1; }; }
        /** Any cell, the ring and the layer above included. */
        int raw(int a, int b, int y) { return a < -1 || b < -1 || a > n || b > n || y < 0 || y > h ? 0 : cells[(y * p + b + 1) * p + a + 1]; }
        /** A cell of the drawn volume; everything outside it counts as empty. */
        int at(int a, int b, int y) { return a < 0 || b < 0 || a >= n || b >= n || y < 0 || y >= h ? 0 : raw(a, b, y); }
        /** Whether the column at a, b (the ring included) is in a chunk that was loaded and read. */
        boolean known(int a, int b) { return columnKnown[(b + 1) * p + a + 1]; }
    }

    /** A player, or the entity the view centres on, drawn as a pillar two blocks tall. */
    private record Mark(String name, double[] pos, boolean player) {}

    private static final int WATER = 1 << 24;
    private static final String[] FROM = {"south-east", "south-west", "north-west", "north-east"};
    private static final long MAX_CELLS = 14_000_000L;
    /** How far below the target, or the ground under it, a view reaches unless "min_y" says otherwise. */
    private static final int BELOW = 8;

    /**
     * An isometric picture of the blocks within "radius" (default 32, up to 64) of the centre, seen from "from"
     * (south-east by default; also south-west, north-west, north-east). "min_y" and "max_y" cut the world to a slab;
     * "cutaway" cuts it at the target's eye level, so a canopy, cave roof or ceiling doesn't hide what is around them.
     * With a target or a height and no "min_y", the slab starts 8 blocks below the target or the ground under it.
     * Blocks are coloured with their map colours, tops brightest, and higher blocks slightly brighter than lower ones;
     * faces where the slab cuts through solid blocks are striped; water is see-through. The picture is cropped to
     * what is drawn and scaled ("scale" pixels per unit, a block being 4 units wide) to the largest a model sees in full.
     *
     * The blocks are copied on the server thread, nearest chunks first, for at most "budget_ms" (default 250, up to
     * 1500); the drawing happens off it.
     */
    static void view(JsonObject p, JsonObject reply) {
        Volume[] holder = new Volume[1];
        JsonObject inner = new JsonObject();
        if (!Bridge.onServerThread(inner, 10_000, () -> { holder[0] = copy(p); return com.google.gson.JsonNull.INSTANCE; })) {
            inner.entrySet().forEach(e -> reply.add(e.getKey(), e.getValue()));
            return;
        }
        try {
            Bridge.ok(reply, draw(holder[0], askedScale(p)));
        } catch (IOException | RuntimeException e) {
            Bridge.fail(reply, "failed", "The blocks were read but the picture could not be drawn: " + e);
        }
    }

    private static Volume copy(JsonObject p) {
        Centre c = centre(p);
        int r = p.has("radius") ? p.get("radius").getAsInt() : 32;
        if (r < 1 || r > 64) throw new Bridge.Refusal("bad_params", "\"radius\" for a view must be between 1 and 64 blocks.");
        askedScale(p);
        String from = p.has("from") ? p.get("from").getAsString() : FROM[0];
        Volume v = new Volume();
        v.turns = List.of(FROM).indexOf(from);
        if (v.turns < 0) throw new Bridge.Refusal("bad_params", "\"from\" must be one of: " + String.join(", ", FROM) + ".");
        v.dimension = c.level.dimension().location().toString();
        v.n = 2 * r + 1; v.p = v.n + 2; v.x0 = c.x - r; v.z0 = c.z - r;
        long budget = Math.max(20, Math.min(1500, p.has("budget_ms") ? p.get("budget_ms").getAsLong() : 250)) * 1_000_000L;

        List<int[]> chunks = new ArrayList<>();
        for (int cx = (v.x0 - 1) >> 4; cx <= (c.x + r + 1) >> 4; cx++)
            for (int cz = (v.z0 - 1) >> 4; cz <= (c.z + r + 1) >> 4; cz++) chunks.add(new int[] {cx, cz});
        chunks.sort(Comparator.comparingLong(ch -> sq(ch[0] * 16 + 8 - c.x) + sq(ch[1] * 16 + 8 - c.z)));

        // The height range that has anything in it, to the nearest 16, so the picture isn't mostly empty sky.
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        List<LevelChunk> loaded = new ArrayList<>();
        // Chunks inside the area itself; the others only hold the ring around it.
        int icx0 = v.x0 >> 4, icx1 = (c.x + r) >> 4, icz0 = v.z0 >> 4, icz1 = (c.z + r) >> 4;
        v.chunksInRange = (icx1 - icx0 + 1) * (icz1 - icz0 + 1);
        for (int[] ch : chunks) {
            LevelChunk chunk = c.level.getChunkSource().getChunkNow(ch[0], ch[1]);
            if (chunk == null) {
                if (ch[0] >= icx0 && ch[0] <= icx1 && ch[1] >= icz0 && ch[1] <= icz1) v.chunksNotLoaded++;
                continue;
            }
            loaded.add(chunk);
            LevelChunkSection[] sections = chunk.getSections();
            for (int s = 0; s < sections.length; s++) {
                if (sections[s] == null || sections[s].hasOnlyAir()) continue;
                int y = chunk.getSectionYFromSectionIndex(s) << 4;
                lo = Math.min(lo, y); hi = Math.max(hi, y + 15);
            }
        }
        if (lo > hi) { lo = c.level.getMinBuildHeight(); hi = lo; }
        v.blocksLo = lo; v.blocksHi = hi;
        int top = hi, bottom = lo;
        Integer cut = cutTop(p, c);
        if (cut != null && cut < top) { top = cut; v.cutTop = true; v.cutWhy = cutReason(p, c, cut); }
        if (p.has("min_y")) bottom = Math.max(bottom, p.get("min_y").getAsInt());
        else if (c.y != null) {
            // The ground under them when it is lower, so someone flying shows with the ground below; nothing under them
            // (between islands, or on an airship) leaves just their own height.
            int ground = c.y;
            LevelChunk here = c.level.getChunkSource().getChunkNow(c.x >> 4, c.z >> 4);
            int surface = here == null ? Integer.MIN_VALUE : here.getHeight(Heightmap.Types.WORLD_SURFACE, c.x & 15, c.z & 15);
            if (surface >= c.level.getMinBuildHeight()) ground = Math.min(ground, surface);
            bottom = Math.max(bottom, Math.min(ground, top) - BELOW);
        }
        if (bottom > top) throw new Bridge.Refusal("empty_range", "There are no blocks between y " + bottom + " and y " + top + " in this area (blocks here span y " + lo + " to " + hi + ").");
        v.yMin = bottom; v.h = top - bottom + 1;
        if ((long) v.p * v.p * (v.h + 1) > MAX_CELLS) {
            throw new Bridge.Refusal("too_large", "That is " + v.n + " x " + v.n + " x " + v.h + " blocks, more than a view can hold. Lower \"radius\", or give \"min_y\" and \"max_y\" (blocks here span y " + lo + " to " + hi + ").");
        }
        v.cells = new int[v.p * v.p * (v.h + 1)];
        v.columnKnown = new boolean[v.p * v.p];

        java.util.IdentityHashMap<BlockState, Integer> colours = new java.util.IdentityHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        long deadline = System.nanoTime() + budget;
        int last = top + 1;     // the layer above the slab
        for (int i = 0; i < loaded.size(); i++) {
            if (System.nanoTime() > deadline) {
                for (LevelChunk left : loaded.subList(i, loaded.size())) {
                    int lx = left.getPos().x, lz = left.getPos().z;
                    if (lx >= icx0 && lx <= icx1 && lz >= icz0 && lz <= icz1) v.chunksNotReached++;
                }
                break;
            }
            LevelChunk chunk = loaded.get(i);
            int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int dx = bx + x - v.x0, dz = bz + z - v.z0;
                if (dx >= -1 && dz >= -1 && dx <= v.n && dz <= v.n) v.columnKnown[v.pb(dx, dz) * v.p + v.pa(dx, dz)] = true;
            }
            LevelChunkSection[] sections = chunk.getSections();
            for (int s = 0; s < sections.length; s++) {
                LevelChunkSection section = sections[s];
                if (section == null || section.hasOnlyAir()) continue;
                int baseY = chunk.getSectionYFromSectionIndex(s) << 4;
                if (baseY > last || baseY + 15 < bottom) continue;
                for (int y = 0; y < 16; y++) {
                    int wy = baseY + y;
                    if (wy < bottom || wy > last) continue;
                    for (int z = 0; z < 16; z++) {
                        int dz = bz + z - v.z0;
                        if (dz < -1 || dz > v.n) continue;
                        for (int x = 0; x < 16; x++) {
                            int dx = bx + x - v.x0;
                            if (dx < -1 || dx > v.n) continue;
                            BlockState state = section.getBlockState(x, y, z);
                            if (state.isAir()) continue;
                            Integer col = colours.get(state);
                            if (col == null) {
                                MapColor mc = state.getMapColor(c.level, pos.set(bx + x, wy, bz + z));
                                colours.put(state, col = mc == MapColor.NONE ? 0 : mc == MapColor.WATER ? mc.col | WATER : mc.col);
                            }
                            if (col != 0) v.cells[((wy - bottom) * v.p + v.pb(dx, dz)) * v.p + v.pa(dx, dz)] = col;
                        }
                    }
                }
            }
        }
        for (ServerPlayer player : c.level.players()) {
            int dx = player.getBlockX() - v.x0, dz = player.getBlockZ() - v.z0;
            if (dx < 0 || dz < 0 || dx >= v.n || dz >= v.n) continue;
            v.marks.add(new Mark(player.getGameProfile().getName(), new double[] {player.getX(), player.getY(), player.getZ()}, true));
        }
        if (c.at != null && !c.player) v.marks.add(new Mark(c.who, c.at, false));
        return v;
    }

    private static final int TOP = 1, LEFT = 2, RIGHT = 4, TOP_CUT = 8, LEFT_CUT = 16, RIGHT_CUT = 32;

    /**
     * Which of a cell's three faces towards the camera show, and which of those are cuts: faces against solid blocks
     * the picture leaves out, or against a column in a chunk that wasn't read, where nothing is known.
     */
    private static int faces(Volume v, int a, int b, int y) {
        int c = v.at(a, b, y);
        if (c == 0) return 0;
        int f = 0;
        if (shows(c, v.at(a, b, y + 1))) f |= TOP;
        if (shows(c, v.at(a, b + 1, y))) f |= LEFT;
        if (shows(c, v.at(a + 1, b, y))) f |= RIGHT;
        if ((c & WATER) == 0) {
            if ((f & TOP) != 0 && y == v.h - 1 && solid(v.raw(a, b, y + 1))) f |= TOP_CUT;
            if ((f & LEFT) != 0 && (!v.known(a, b + 1) || b == v.n - 1 && solid(v.raw(a, b + 1, y)))) f |= LEFT_CUT;
            if ((f & RIGHT) != 0 && (!v.known(a + 1, b) || a == v.n - 1 && solid(v.raw(a + 1, b, y)))) f |= RIGHT_CUT;
        }
        return f;
    }

    /** A face shows when the cell across it is empty, or is water and this cell isn't. */
    private static boolean shows(int self, int other) {
        return other == 0 || (other & WATER) != 0 && (self & WATER) == 0;
    }

    private static boolean solid(int cell) {
        return cell != 0 && (cell & WATER) == 0;
    }

    /**
     * The picture being drawn: pixels, the depth (a + b + y) of the solid block each pixel last showed, and whether
     * water already tints it. Water tints a pixel once whatever number of water faces lie over it, so that the
     * overlapping edges of neighbouring faces don't show as a grid.
     */
    private static final class Canvas {
        final int w, h, t, ox, oy, unit;
        final byte[] px;
        final int[] depth;
        final boolean[] tinted;

        Canvas(int w, int h, int t, int ox, int oy) {
            this.w = w; this.h = h; this.t = t; this.ox = ox; this.oy = oy; this.unit = Math.max(1, t / 3);
            px = new byte[w * h * 3];
            depth = new int[w * h];
            tinted = new boolean[w * h];
            java.util.Arrays.fill(depth, Integer.MIN_VALUE);
            for (int i = 0; i < w * h; i++) { px[i * 3] = (byte) (VOID >> 16); px[i * 3 + 1] = (byte) (VOID >> 8); px[i * 3 + 2] = (byte) VOID; }
        }

        int sx(int a, int b) { return ox + (a - b) * 2 * t; }
        int sy(int a, int b, int y) { return oy + (a + b) * t - y * 2 * t; }

        interface Pixel { void at(int x, int y, int face); }

        /** Visits the pixels of a block's shown faces. A block is 4t wide; its top is 2t tall and its two sides 2t tall. */
        void cube(int sx, int sy, int faces, Pixel visit) {
            for (int dx = -2 * t; dx < 2 * t; dx++) {
                int face = dx < 0 ? LEFT : RIGHT;
                if ((faces & face) == 0) continue;
                int k = (dx < 0 ? dx + 2 * t : 2 * t - 1 - dx) / 2;
                for (int dy = 0; dy < 2 * t; dy++) visit.at(sx + dx, sy + t + k + dy, face);
            }
            if ((faces & TOP) != 0) {
                for (int dy = 0; dy < 2 * t; dy++) {
                    int half = Math.min(2 * t, 2 * (Math.min(dy, 2 * t - 1 - dy) + 1));
                    for (int dx = -half; dx < half; dx++) visit.at(sx + dx, sy + dy, TOP);
                }
            }
        }

        /** Paints a block's faces: water blended over what is behind it, cut faces striped, solid faces recorded in the depth buffer. */
        void paint(int sx, int sy, int faces, int rgb, double light, int d) {
            boolean water = (rgb & WATER) != 0;
            cube(sx, sy, faces, (x, y, face) -> {
                if (x < 0 || y < 0 || x >= w || y >= h) return;
                double shade = light * (face == LEFT ? 0.60 : face == RIGHT ? 0.80 : 1.0);
                if ((faces & face << 3) != 0 && stripe(x, y, unit)) shade *= 0.45;
                int c = blend(0, rgb & 0xFFFFFF, Math.min(1, shade)), i = y * w + x, o = i * 3;
                if (water) {
                    if (tinted[i]) return;
                    tinted[i] = true;
                    c = blend((px[o] & 255) << 16 | (px[o + 1] & 255) << 8 | px[o + 2] & 255, c, WATER_ALPHA);
                } else {
                    depth[i] = d;
                    tinted[i] = false;
                }
                px[o] = (byte) (c >> 16); px[o + 1] = (byte) (c >> 8); px[o + 2] = (byte) c;
            });
        }
    }

    private static JsonObject draw(Volume v, int asked) throws IOException {
        int n = v.n;
        // First pass: how far the drawn blocks reach, in units of t, so the picture is cropped to them and scaled to fit.
        int[] box = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
        boolean any = false;
        for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) {
            if (!v.known(a, b)) { reach(box, a, b, 0); any = true; }
            for (int y = 0; y < v.h; y++) if (faces(v, a, b, y) != 0) { reach(box, a, b, y); any = true; }
        }
        List<int[]> markers = new ArrayList<>();   // a, b, y of each mark's feet, or null when outside the slab
        for (Mark mk : v.marks) {
            double[] q = mk.pos;
            int dx = Mth.floor(q[0]) - v.x0, dz = Mth.floor(q[2]) - v.z0, y = Mth.floor(q[1]) - v.yMin;
            int a = v.pa(dx, dz) - 1, b = v.pb(dx, dz) - 1;
            if (y < -1 || y > v.h) { markers.add(null); continue; }
            markers.add(new int[] {a, b, y});
            reach(box, a, b, y); reach(box, a, b, y + 1);
        }
        if (!any && markers.stream().allMatch(java.util.Objects::isNull)) { reach(box, 0, 0, 0); reach(box, n - 1, n - 1, 0); reach(box, n - 1, 0, 0); reach(box, 0, n - 1, 0); }
        int uw = box[1] - box[0] + 2, uh = box[3] - box[2] + 2;
        int t = asked > 0 ? asked : fit(uw, uh, MAX_SCALE);
        Canvas cv = new Canvas(uw * t, uh * t, t, (1 - box[0]) * t, (1 - box[2]) * t);

        // Ground tiles for columns in chunks that were not loaded or not reached, so they don't read as void.
        for (int d = 0; d <= 2 * (n - 1); d++) for (int a = Math.max(0, d - n + 1); a <= Math.min(d, n - 1); a++) {
            int b = d - a;
            if (!v.known(a, b)) cv.paint(cv.sx(a, b), cv.sy(a, b, 0), TOP, NOT_LOADED, 1.0, Integer.MIN_VALUE);
        }
        int drawn = 0, cutFaces = 0, waterFaces = 0;
        for (int d = 0; d <= 2 * (n - 1); d++) for (int a = Math.max(0, d - n + 1); a <= Math.min(d, n - 1); a++) {
            int b = d - a;
            for (int y = 0; y < v.h; y++) {
                int f = faces(v, a, b, y);
                if (f == 0) continue;
                int col = v.at(a, b, y);
                double light = 0.72 + 0.28 * (v.h <= 1 ? 1.0 : (double) y / (v.h - 1));
                cv.paint(cv.sx(a, b), cv.sy(a, b, y), f, col, light, a + b + y);
                drawn++;
                if (f >= TOP_CUT) cutFaces++;
                if ((col & WATER) != 0) waterFaces++;
            }
        }

        // Players, and the entity the view centres on: a pillar two blocks tall drawn over everything, with how much of
        // it the blocks in front would hide.
        JsonArray players = new JsonArray();
        JsonObject target = null;
        List<String> hidden = new ArrayList<>();
        for (int i = 0; i < v.marks.size(); i++) {
            Mark mk = v.marks.get(i);
            double[] q = mk.pos;
            JsonObject pl = new JsonObject();
            pl.addProperty("name", mk.name);
            pl.add("pos", Methods.pos(q[0], q[1], q[2]));
            if (mk.player) players.add(pl); else target = pl;
            int[] m = markers.get(i);
            if (m == null) {
                pl.addProperty("outside", "at y " + Mth.floor(q[1]) + ", " + (Mth.floor(q[1]) < v.yMin ? "below" : "above") + " the slab drawn, so not marked");
                continue;
            }
            int[] count = {0, 0};
            for (int k = 0; k < 2; k++) {
                int front = m[0] + m[1] + m[2] + k;
                cv.cube(cv.sx(m[0], m[1]), cv.sy(m[0], m[1], m[2] + k), TOP | LEFT | RIGHT, (x, y, face) -> {
                    if (x < 0 || y < 0 || x >= cv.w || y >= cv.h) return;
                    count[1]++;
                    if (cv.depth[y * cv.w + x] > front) count[0]++;
                });
            }
            for (int k = 0; k < 2; k++) cv.paint(cv.sx(m[0], m[1]), cv.sy(m[0], m[1], m[2] + k), TOP | LEFT | RIGHT, mk.player ? MARKER : TARGET_MARKER, 1.0, Integer.MIN_VALUE);
            int pct = count[1] == 0 ? 0 : Math.round(100f * count[0] / count[1]);
            JsonArray at = new JsonArray(); at.add(cv.sx(m[0], m[1])); at.add(cv.sy(m[0], m[1], m[2]));
            pl.add("pixel", at);
            pl.addProperty("hidden_percent", pct);
            if (pct >= 25) hidden.add(mk.name + " (" + pct + "%)");
        }

        JsonObject o = new JsonObject();
        o.addProperty("dimension", v.dimension);
        o.addProperty("width", cv.w);
        o.addProperty("height", cv.h);
        o.addProperty("seen_from", FROM[v.turns]);
        o.addProperty("reading", "The camera is to the " + FROM[v.turns] + " and above, looking down across the area; up on the picture is away from the camera and higher. "
                + "Tops are brightest, the two visible sides darker, and higher blocks slightly brighter than lower ones. "
                + "Faces striped with dark diagonal lines are where the picture cuts through solid blocks (the top of the slab, or the area's two near edges) or stops at a chunk that isn't loaded: they are not real floors or cliffs. "
                + "Water is see-through, tinted blue.");
        JsonArray corner = new JsonArray(); corner.add(v.x0); corner.add(v.z0);
        o.add("north_west_corner_block", corner);
        o.addProperty("blocks_across", n);
        o.addProperty("y_from", v.yMin);
        o.addProperty("y_to", v.yMin + v.h - 1);
        List<String> range = new ArrayList<>();
        if (v.cutTop) range.add("Everything above y " + (v.yMin + v.h - 1) + " is cut away (" + v.cutWhy + ").");
        if (v.yMin > v.blocksLo + 15) range.add("Blocks below y " + v.yMin + " are left out; the lowest in this area are between y " + v.blocksLo + " and " + (v.blocksLo + 15) + ". \"min_y\" includes them.");
        else if (v.yMin > v.blocksLo) range.add("There may be a few blocks below y " + v.yMin + ", down to y " + v.blocksLo + " at most; \"min_y\" includes them.");
        if (!range.isEmpty()) o.addProperty("range", String.join(" ", range));
        o.addProperty("pixels_per_block_across", 4 * t);
        o.addProperty("pixels_per_block_up", 2 * t);
        o.addProperty("blocks_drawn", drawn);
        o.addProperty("cut_faces", cutFaces);
        o.addProperty("water_faces", waterFaces);
        o.addProperty("chunks_in_range", v.chunksInRange);
        o.addProperty("chunks_not_loaded", v.chunksNotLoaded);
        o.addProperty("chunks_not_reached", v.chunksNotReached);
        o.addProperty("complete", v.chunksNotLoaded == 0 && v.chunksNotReached == 0);
        if (v.chunksNotLoaded + v.chunksNotReached > 0) {
            o.addProperty("note", (v.chunksNotLoaded > 0 ? v.chunksNotLoaded + " chunks in range are not loaded. " : "")
                    + (v.chunksNotReached > 0 ? v.chunksNotReached + " loaded chunks were not copied before the time budget ran out (nearest go first); raise \"budget_ms\" or lower \"radius\". " : "")
                    + "Their ground is drawn as flat dark purple tiles at the lowest level, nothing in them is shown, and the faces of blocks beside them are striped as cuts, since what lies past them is unknown.");
        }
        o.addProperty("player_marker", "bright red pillar two blocks tall, drawn over any blocks in front of it");
        if (target != null) {
            target.addProperty("marker", "yellow pillar two blocks tall, drawn over any blocks in front of it");
            o.add("target", target);
        }
        if (!hidden.isEmpty()) {
            o.addProperty("hidden", "Blocks in front of " + String.join(", ", hidden) + " cover that much of the marker, so the picture shows little of what is around them. "
                    + (v.cutTop ? "What is in front of them is below the cut: look from another side, or cut lower with \"max_y\"." : "\"cutaway\" removes everything above their eye level; looking from another side can also help."));
        }
        o.add("players_in_view", players);
        o.addProperty("png_base64", Base64.getEncoder().encodeToString(png(cv.px, cv.w, cv.h)));
        return o;
    }

    /** Widens box (x min, x max, y min, y max, in units of t) to take in a block drawn at a, b, y. */
    private static void reach(int[] box, int a, int b, int y) {
        int x = 2 * (a - b), top = a + b - 2 * y;
        box[0] = Math.min(box[0], x - 2); box[1] = Math.max(box[1], x + 2);
        box[2] = Math.min(box[2], top); box[3] = Math.max(box[3], top + 4);
    }

    /** A plain 8-bit RGB PNG. */
    private static byte[] png(byte[] rgb, int w, int height) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(w * height * 3 / 4 + 64);
        try (DeflaterOutputStream z = new DeflaterOutputStream(raw, new Deflater(Deflater.BEST_SPEED))) {
            for (int row = 0; row < height; row++) {
                z.write(0);
                z.write(rgb, row * w * 3, w * 3);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(head);
        h.writeInt(w); h.writeInt(height); h.write(new byte[] {8, 2, 0, 0, 0});
        chunk(d, "IHDR", head.toByteArray());
        chunk(d, "IDAT", raw.toByteArray());
        chunk(d, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(DataOutputStream d, String type, byte[] data) throws IOException {
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        d.writeInt(data.length);
        d.write(t); d.write(data);
        CRC32 crc = new CRC32();
        crc.update(t); crc.update(data);
        d.writeInt((int) crc.getValue());
    }

    private static JsonArray xz(int x, int z) {
        JsonArray a = new JsonArray();
        a.add(x); a.add(z);
        return a;
    }

    private static long sq(long v) { return v * v; }

    private static double sq(double v) { return v * v; }
}
