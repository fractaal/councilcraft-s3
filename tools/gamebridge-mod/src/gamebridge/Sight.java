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

    /** The centre of a request: "target" (a player or entity) or "near": [x, z] or [x, y, z], with "dimension". */
    private record Centre(ServerLevel level, int x, int z) {}

    private static Centre centre(JsonObject p) {
        if (p.has("target")) {
            var e = Methods.one(p);
            return new Centre((ServerLevel) e.level(), e.getBlockX(), e.getBlockZ());
        }
        if (!p.has("near")) throw new Bridge.Refusal("bad_params", "This needs \"target\" (a player or entity to centre on) or \"near\": [x, z].");
        JsonArray n = p.getAsJsonArray("near");
        return new Centre(Methods.level(p), (int) Math.floor(n.get(0).getAsDouble()), (int) Math.floor(n.get(n.size() - 1).getAsDouble()));
    }

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

    private static final int NOT_LOADED = 0x3A0A3A, VOID = 0x05070F;

    /**
     * A top-down picture of the area within "radius" blocks (default 96) of the centre, one pixel per block times
     * "scale" (1-4), north up, coloured with the same map colours the in-game map item uses and shaded by slope.
     * Returned as a base64 PNG with what is needed to read it: the world position of the top-left pixel, the two
     * colours that mean "chunk not loaded" and "nothing there (void)", the height range, and where players are.
     */
    static JsonElement map(JsonObject p) throws IOException {
        Centre c = centre(p);
        int r = radius(p, 96);
        int scale = p.has("scale") ? p.get("scale").getAsInt() : 1;
        if (scale < 1 || scale > 4) throw new Bridge.Refusal("bad_params", "\"scale\" must be 1 to 4 pixels per block.");
        int size = 2 * r + 1, x0 = c.x - r, z0 = c.z - r;
        int floor = c.level.getMinBuildHeight();
        int[] height = new int[size * (size + 1)];      // one extra row to the north, for slope shading
        int[] colour = new int[size * size];
        boolean[] loaded = new boolean[size * (size + 1)];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LevelChunk chunk = null;
        int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE, missing = 0, voids = 0, lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int row = -1; row < size; row++) {
            for (int col = 0; col < size; col++) {
                int wx = x0 + col, wz = z0 + row, i = (row + 1) * size + col;
                if (wx >> 4 != chunkX || wz >> 4 != chunkZ) {
                    chunkX = wx >> 4; chunkZ = wz >> 4;
                    chunk = c.level.getChunkSource().getChunkNow(chunkX, chunkZ);
                }
                if (chunk == null) { height[i] = floor; continue; }
                loaded[i] = true;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);
                int rgb = 0;
                // Skip things with no map colour (glass, torches, flowers' stems) to the first block that has one.
                for (int down = 0; down < 24 && y >= floor; down++, y--) {
                    BlockState state = chunk.getBlockState(pos.set(wx, y, wz));
                    MapColor mc = state.getMapColor(c.level, pos);
                    if (mc != MapColor.NONE) { rgb = mc.col; break; }
                }
                height[i] = Math.max(y, floor);
                if (row >= 0) colour[row * size + col] = rgb == 0 ? -1 : rgb;
            }
        }
        byte[] pixels = new byte[size * size * 3];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                int i = (row + 1) * size + col, north = row * size + col, rgb;
                if (!loaded[i]) { rgb = NOT_LOADED; missing++; }
                else if (colour[row * size + col] == -1) { rgb = VOID; voids++; }
                else {
                    int h = height[i];
                    lowest = Math.min(lowest, h); highest = Math.max(highest, h);
                    int shade = !loaded[north] || h == height[north] ? 220 : h > height[north] ? 255 : 180;
                    int base = colour[row * size + col];
                    rgb = ((base >> 16 & 255) * shade / 255) << 16 | ((base >> 8 & 255) * shade / 255) << 8 | (base & 255) * shade / 255;
                }
                int o = (row * size + col) * 3;
                pixels[o] = (byte) (rgb >> 16); pixels[o + 1] = (byte) (rgb >> 8); pixels[o + 2] = (byte) rgb;
            }
        }
        JsonArray players = new JsonArray();
        for (ServerPlayer player : c.level.players()) {
            int col = player.getBlockX() - x0, row = player.getBlockZ() - z0;
            if (col < 0 || row < 0 || col >= size || row >= size) continue;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                int px = col + dx, py = row + dy;
                if (px < 0 || py < 0 || px >= size || py >= size) continue;
                boolean edge = Math.abs(dx) == 2 || Math.abs(dy) == 2;
                int o = (py * size + px) * 3;
                pixels[o] = (byte) 255; pixels[o + 1] = (byte) (edge ? 255 : 0); pixels[o + 2] = (byte) (edge ? 255 : 0);
            }
            JsonObject pl = new JsonObject();
            pl.addProperty("name", player.getGameProfile().getName());
            pl.add("pos", Methods.pos(player.getX(), player.getY(), player.getZ()));
            JsonArray px = new JsonArray(); px.add(col * scale + scale / 2); px.add(row * scale + scale / 2);
            pl.add("pixel", px);
            players.add(pl);
        }
        JsonObject o = new JsonObject();
        o.addProperty("dimension", c.level.dimension().location().toString());
        o.addProperty("width", size * scale);
        o.addProperty("height", size * scale);
        o.addProperty("pixels_per_block", scale);
        o.add("top_left_block", xz(x0, z0));
        o.add("centre_block", xz(c.x, c.z));
        o.addProperty("north", "up");
        o.addProperty("colour_not_loaded", "#3A0A3A (dark purple): the chunk is not loaded, so nothing is known about it");
        o.addProperty("colour_void", "#05070F (near black): loaded, and there is no block in that column");
        o.addProperty("not_loaded_percent", Math.round(1000.0 * missing / (size * size)) / 10.0);
        o.addProperty("void_percent", Math.round(1000.0 * voids / (size * size)) / 10.0);
        if (highest >= lowest) { o.addProperty("lowest_surface_y", lowest); o.addProperty("highest_surface_y", highest); }
        o.addProperty("shading", "a column higher than its north neighbour is brighter, lower is darker");
        o.addProperty("player_marker", "red square with a white border");
        o.add("players_in_view", players);
        byte[] big = pixels;
        if (scale > 1) {
            int w = size * scale;
            big = new byte[w * w * 3];
            for (int y = 0; y < w; y++) for (int x = 0; x < w; x++) {
                int from = ((y / scale) * size + x / scale) * 3, to = (y * w + x) * 3;
                big[to] = pixels[from]; big[to + 1] = pixels[from + 1]; big[to + 2] = pixels[from + 2];
            }
        }
        o.addProperty("png_base64", Base64.getEncoder().encodeToString(png(big, size * scale, size * scale)));
        return o;
    }

    // ---- isometric picture ----

    /** The blocks of an area as map colours (0 = nothing drawable), already turned to the view's direction. */
    private static final class Volume {
        ServerLevel level;
        int size, x0, z0, yMin, h, turns, chunksInRange, chunksNotLoaded, chunksNotReached;
        int[] cells;
        boolean[] columnKnown;
        List<double[]> players = new ArrayList<>();
        List<String> playerNames = new ArrayList<>();

        int a(int dx, int dz) { return switch (turns) { case 1 -> dz; case 2 -> size - 1 - dx; case 3 -> size - 1 - dz; default -> dx; }; }
        int b(int dx, int dz) { return switch (turns) { case 1 -> size - 1 - dx; case 2 -> size - 1 - dz; case 3 -> dx; default -> dz; }; }
        int at(int a, int b, int y) { return a < 0 || b < 0 || y < 0 || a >= size || b >= size || y >= h ? 0 : cells[(y * size + b) * size + a]; }
    }

    private static final String[] FROM = {"south-east", "south-west", "north-west", "north-east"};
    private static final long MAX_CELLS = 14_000_000L;

    /**
     * An isometric picture of the blocks within "radius" (default 32, up to 64) of the centre, seen from "from"
     * (south-east by default; also south-west, north-west, north-east), "scale" 1 or 2. "min_y" and "max_y" cut the
     * world to a slab, which is how to look under a roof or at one floor. Blocks are coloured with their map colours,
     * tops brightest, and higher blocks slightly brighter than lower ones.
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
            Bridge.ok(reply, draw(holder[0], p.has("scale") ? p.get("scale").getAsInt() : 1));
        } catch (IOException | RuntimeException e) {
            Bridge.fail(reply, "failed", "The blocks were read but the picture could not be drawn: " + e);
        }
    }

    private static Volume copy(JsonObject p) {
        Centre c = centre(p);
        int r = p.has("radius") ? p.get("radius").getAsInt() : 32;
        if (r < 1 || r > 64) throw new Bridge.Refusal("bad_params", "\"radius\" for a view must be between 1 and 64 blocks.");
        int scale = p.has("scale") ? p.get("scale").getAsInt() : 1;
        if (scale < 1 || scale > 2) throw new Bridge.Refusal("bad_params", "\"scale\" for a view must be 1 or 2.");
        String from = p.has("from") ? p.get("from").getAsString() : FROM[0];
        Volume v = new Volume();
        v.turns = List.of(FROM).indexOf(from);
        if (v.turns < 0) throw new Bridge.Refusal("bad_params", "\"from\" must be one of: " + String.join(", ", FROM) + ".");
        v.level = c.level; v.size = 2 * r + 1; v.x0 = c.x - r; v.z0 = c.z - r;
        long budget = Math.max(20, Math.min(1500, p.has("budget_ms") ? p.get("budget_ms").getAsLong() : 250)) * 1_000_000L;

        List<int[]> chunks = new ArrayList<>();
        for (int cx = v.x0 >> 4; cx <= (c.x + r) >> 4; cx++)
            for (int cz = v.z0 >> 4; cz <= (c.z + r) >> 4; cz++) chunks.add(new int[] {cx, cz});
        chunks.sort(Comparator.comparingLong(ch -> sq(ch[0] * 16 + 8 - c.x) + sq(ch[1] * 16 + 8 - c.z)));
        v.chunksInRange = chunks.size();

        // The height range that has anything in it, so the picture isn't mostly empty sky.
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        List<LevelChunk> loaded = new ArrayList<>();
        for (int[] ch : chunks) {
            LevelChunk chunk = c.level.getChunkSource().getChunkNow(ch[0], ch[1]);
            if (chunk == null) { v.chunksNotLoaded++; continue; }
            loaded.add(chunk);
            LevelChunkSection[] sections = chunk.getSections();
            for (int s = 0; s < sections.length; s++) {
                if (sections[s] == null || sections[s].hasOnlyAir()) continue;
                int y = chunk.getSectionYFromSectionIndex(s) << 4;
                lo = Math.min(lo, y); hi = Math.max(hi, y + 15);
            }
        }
        if (lo > hi) { lo = c.level.getMinBuildHeight(); hi = lo; }
        if (p.has("min_y")) lo = Math.max(lo, p.get("min_y").getAsInt());
        if (p.has("max_y")) hi = Math.min(hi, p.get("max_y").getAsInt());
        if (lo > hi) throw new Bridge.Refusal("empty_range", "There are no blocks between the given min_y and max_y in this area.");
        v.yMin = lo; v.h = hi - lo + 1;
        if ((long) v.size * v.size * v.h > MAX_CELLS) {
            throw new Bridge.Refusal("too_large", "That is " + v.size + " x " + v.size + " x " + v.h + " blocks, more than a view can hold. Lower \"radius\", or give \"min_y\" and \"max_y\" (blocks here span y " + lo + " to " + hi + ").");
        }
        v.cells = new int[v.size * v.size * v.h];
        v.columnKnown = new boolean[v.size * v.size];

        java.util.IdentityHashMap<BlockState, Integer> colours = new java.util.IdentityHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        long deadline = System.nanoTime() + budget;
        for (int i = 0; i < loaded.size(); i++) {
            if (System.nanoTime() > deadline) { v.chunksNotReached = loaded.size() - i; break; }
            LevelChunk chunk = loaded.get(i);
            int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int dx = bx + x - v.x0, dz = bz + z - v.z0;
                if (dx >= 0 && dz >= 0 && dx < v.size && dz < v.size) v.columnKnown[v.b(dx, dz) * v.size + v.a(dx, dz)] = true;
            }
            LevelChunkSection[] sections = chunk.getSections();
            for (int s = 0; s < sections.length; s++) {
                LevelChunkSection section = sections[s];
                if (section == null || section.hasOnlyAir()) continue;
                int baseY = chunk.getSectionYFromSectionIndex(s) << 4;
                if (baseY > hi || baseY + 15 < lo) continue;
                for (int y = 0; y < 16; y++) {
                    int wy = baseY + y;
                    if (wy < lo || wy > hi) continue;
                    for (int z = 0; z < 16; z++) {
                        int dz = bz + z - v.z0;
                        if (dz < 0 || dz >= v.size) continue;
                        for (int x = 0; x < 16; x++) {
                            int dx = bx + x - v.x0;
                            if (dx < 0 || dx >= v.size) continue;
                            BlockState state = section.getBlockState(x, y, z);
                            if (state.isAir()) continue;
                            Integer col = colours.get(state);
                            if (col == null) colours.put(state, col = state.getMapColor(c.level, pos.set(bx + x, wy, bz + z)).col);
                            if (col != 0) v.cells[((wy - lo) * v.size + v.b(dx, dz)) * v.size + v.a(dx, dz)] = col;
                        }
                    }
                }
            }
        }
        for (ServerPlayer player : c.level.players()) {
            int dx = player.getBlockX() - v.x0, dz = player.getBlockZ() - v.z0;
            if (dx < 0 || dz < 0 || dx >= v.size || dz >= v.size) continue;
            v.players.add(new double[] {player.getX(), player.getY(), player.getZ()});
            v.playerNames.add(player.getGameProfile().getName());
        }
        return v;
    }

    /** Draws back to front. A block is 4t pixels wide; its top is 2t tall and its two visible sides 2t tall. */
    private static JsonObject draw(Volume v, int t) throws IOException {
        int n = v.size, w = 4 * t * n, top = 2 * t * v.h, imgH = 2 * t * n + top + 2 * t;
        int ox = 2 * t * n, oy = top;                  // where block (a=0, b=0, y=yMin) puts the top corner of its top face
        byte[] px = new byte[w * imgH * 3];
        for (int i = 0; i < w * imgH; i++) { px[i * 3] = (byte) (VOID >> 16); px[i * 3 + 1] = (byte) (VOID >> 8); px[i * 3 + 2] = (byte) VOID; }
        // Ground markers for columns in chunks that were not loaded or not reached, so they don't read as void.
        for (int d = 0; d <= 2 * (n - 1); d++) for (int a = Math.max(0, d - n + 1); a <= Math.min(d, n - 1); a++) {
            int b = d - a;
            if (!v.columnKnown[b * n + a]) cube(px, w, imgH, ox + (a - b) * 2 * t, oy + (a + b) * t, t, NOT_LOADED, true, false, false, 1.0);
        }
        int drawn = 0;
        for (int d = 0; d <= 2 * (n - 1); d++) for (int a = Math.max(0, d - n + 1); a <= Math.min(d, n - 1); a++) {
            int b = d - a;
            for (int y = 0; y < v.h; y++) {
                int col = v.at(a, b, y);
                if (col == 0) continue;
                boolean up = v.at(a, b, y + 1) == 0, right = a == n - 1 || v.at(a + 1, b, y) == 0, left = b == n - 1 || v.at(a, b + 1, y) == 0;
                if (!up && !right && !left) continue;
                double light = 0.72 + 0.28 * (v.h <= 1 ? 1.0 : (double) y / (v.h - 1));
                cube(px, w, imgH, ox + (a - b) * 2 * t, oy + (a + b) * t - y * 2 * t, t, col, up, left, right, light);
                drawn++;
            }
        }
        JsonArray players = new JsonArray();
        for (int i = 0; i < v.players.size(); i++) {
            double[] q = v.players.get(i);
            int dx = (int) Math.floor(q[0]) - v.x0, dz = (int) Math.floor(q[2]) - v.z0, a = v.a(dx, dz), b = v.b(dx, dz), y = (int) Math.floor(q[1]) - v.yMin;
            int sx = ox + (a - b) * 2 * t, sy = oy + (a + b) * t - y * 2 * t;
            for (int k = 0; k < 2; k++) cube(px, w, imgH, sx, sy - k * 2 * t, t, 0xFF2020, true, true, true, 1.0);
            JsonObject pl = new JsonObject();
            pl.addProperty("name", v.playerNames.get(i));
            pl.add("pos", Methods.pos(q[0], q[1], q[2]));
            JsonArray at = new JsonArray(); at.add(sx); at.add(sy);
            pl.add("pixel", at);
            players.add(pl);
        }
        JsonObject o = new JsonObject();
        o.addProperty("dimension", v.level.dimension().location().toString());
        o.addProperty("width", w);
        o.addProperty("height", imgH);
        o.addProperty("seen_from", FROM[v.turns]);
        o.addProperty("reading", "The camera is to the " + FROM[v.turns] + " and above, looking down across the area; up on the picture is away from the camera and higher. "
                + "Tops are brightest, the two visible sides darker, and higher blocks slightly brighter than lower ones. "
                + "The flat walls along the two near edges are where the area was cut off, not cliffs.");
        JsonArray corner = new JsonArray(); corner.add(v.x0); corner.add(v.z0);
        o.add("north_west_corner_block", corner);
        o.addProperty("blocks_across", n);
        o.addProperty("y_from", v.yMin);
        o.addProperty("y_to", v.yMin + v.h - 1);
        o.addProperty("pixels_per_block_up", 2 * t);
        o.addProperty("blocks_drawn", drawn);
        o.addProperty("chunks_in_range", v.chunksInRange);
        o.addProperty("chunks_not_loaded", v.chunksNotLoaded);
        o.addProperty("chunks_not_reached", v.chunksNotReached);
        o.addProperty("complete", v.chunksNotLoaded == 0 && v.chunksNotReached == 0);
        if (v.chunksNotLoaded + v.chunksNotReached > 0) {
            o.addProperty("note", (v.chunksNotLoaded > 0 ? v.chunksNotLoaded + " chunks in range are not loaded. " : "")
                    + (v.chunksNotReached > 0 ? v.chunksNotReached + " loaded chunks were not copied before the time budget ran out (nearest go first); raise \"budget_ms\" or lower \"radius\". " : "")
                    + "Their ground is drawn as flat dark purple tiles at the lowest level, and nothing in them is shown.");
        }
        o.addProperty("player_marker", "bright red pillar two blocks tall");
        o.add("players_in_view", players);
        o.addProperty("png_base64", Base64.getEncoder().encodeToString(png(px, w, imgH)));
        return o;
    }

    private static void cube(byte[] px, int w, int h, int sx, int sy, int t, int rgb, boolean top, boolean left, boolean right, double light) {
        if (left || right) {
            for (int dx = -2 * t; dx < 2 * t; dx++) {
                boolean isLeft = dx < 0;
                if (isLeft ? !left : !right) continue;
                int k = (isLeft ? dx + 2 * t : 2 * t - 1 - dx) / 2;
                double shade = light * (isLeft ? 0.60 : 0.80);
                for (int dy = 0; dy < 2 * t; dy++) put(px, w, h, sx + dx, sy + t + k + dy, rgb, shade);
            }
        }
        if (top) {
            for (int dy = 0; dy < 2 * t; dy++) {
                int half = Math.min(2 * t, 2 * (Math.min(dy, 2 * t - 1 - dy) + 1));
                for (int dx = -half; dx < half; dx++) put(px, w, h, sx + dx, sy + dy, rgb, light);
            }
        }
    }

    private static void put(byte[] px, int w, int h, int x, int y, int rgb, double shade) {
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        int o = (y * w + x) * 3;
        px[o] = (byte) Math.min(255, (int) ((rgb >> 16 & 255) * shade));
        px[o + 1] = (byte) Math.min(255, (int) ((rgb >> 8 & 255) * shade));
        px[o + 2] = (byte) Math.min(255, (int) ((rgb & 255) * shade));
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
