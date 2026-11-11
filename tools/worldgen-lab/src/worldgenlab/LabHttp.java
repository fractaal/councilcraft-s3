package worldgenlab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;

/**
 * Local web server for the lab. Binds to 127.0.0.1 only.
 *
 * <pre>
 * GET  /                    the viewer (files from -Dworldgenlab.web)
 * GET  /api/settings        current settings and version
 * POST /api/settings        apply new settings (JSON body); answers with the new version or the decode error
 * POST /api/save            write the current settings to the settings file
 * GET  /api/tile?tx&tz&step&v   a 64x64 grid of columns, sampled every `step` blocks, with a one-sample apron
 * GET  /api/compare?cx&cz   lab chunk against the real world's chunk at the same position
 * GET  /api/fullgen?cx&cz&r generate every chunk within r of (cx, cz) to FULL in the real world and time it
 * GET  /api/fastcheck?cx&cz&n  the world's generator with skyislands' FastGen shortcuts on and off, block by block
 * GET  /api/colhash?cx&cz&r  a hash of every block column of the world's chunks within r of (cx, cz)
 * </pre>
 */
final class LabHttp {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final int TILE = 64;

    private static HttpServer http;
    private static ExecutorService genPool;
    private static MinecraftServer server;
    private static JsonObject template;
    private static Path settingsFile, webDir;
    private static volatile LabWorld world;
    private static final AtomicInteger versions = new AtomicInteger();

    static synchronized void start(MinecraftServer s) {
        try {
            server = s;
            settingsFile = Path.of(System.getProperty("worldgenlab.settings"));
            webDir = Path.of(System.getProperty("worldgenlab.web"));
            template = JsonParser.parseString(Files.readString(Path.of(System.getProperty("worldgenlab.template")))).getAsJsonObject();
            int threads = Integer.getInteger("worldgenlab.threads", Math.max(2, Runtime.getRuntime().availableProcessors() - 4));
            genPool = Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "worldgenlab-gen");
                t.setDaemon(true);
                return t;
            });
            JsonObject settings = JsonParser.parseString(Files.readString(settingsFile)).getAsJsonObject();
            world = new LabWorld(server, template, settings, versions.incrementAndGet(), genPool);

            int port = Integer.getInteger("worldgenlab.port", 8765);
            http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 64);
            http.setExecutor(Executors.newFixedThreadPool(16, r -> {
                Thread t = new Thread(r, "worldgenlab-http");
                t.setDaemon(true);
                return t;
            }));
            http.createContext("/", LabHttp::handle);
            http.start();
            WorldgenLab.LOG.info("Worldgen lab ready at http://127.0.0.1:{}/ ({} generation threads)", port, threads);
        } catch (Exception e) {
            WorldgenLab.LOG.error("Worldgen lab failed to start", e);
        }
    }

    static synchronized void stop() {
        if (http != null) http.stop(0);
        if (genPool != null) genPool.shutdownNow();
    }

    private static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = query(ex.getRequestURI());
            switch (path) {
                case "/api/settings" -> {
                    if ("POST".equals(ex.getRequestMethod())) applySettings(ex);
                    else json(ex, 200, state());
                }
                case "/api/save" -> {
                    Files.writeString(settingsFile, GSON.toJson(world.settings) + "\n");
                    JsonObject r = state();
                    r.addProperty("saved", settingsFile.toString());
                    json(ex, 200, r);
                }
                case "/api/tile" -> tile(ex, q);
                case "/api/compare" -> compare(ex, q);
                case "/api/features" -> features(ex, q);
                case "/api/map" -> map(ex, q);
                case "/api/fullgen" -> fullgen(ex, q);
                case "/api/fastcheck" -> fastcheck(ex, q);
                case "/api/colhash" -> colhash(ex, q);
                case "/api/beard" -> beard(ex, q);
                case "/api/waterwalls" -> waterwalls(ex, q);
                default -> serveFile(ex, path);
            }
        } catch (Exception e) {
            WorldgenLab.LOG.warn("Worldgen lab request {} failed", ex.getRequestURI(), e);
            JsonObject r = new JsonObject();
            r.addProperty("error", String.valueOf(e));
            json(ex, 500, r);
        } finally {
            ex.close();
        }
    }

    private static JsonObject state() {
        LabWorld w = world;
        JsonObject r = new JsonObject();
        r.addProperty("version", w.version);
        r.addProperty("seed", w.seed);
        r.addProperty("minY", w.minY);
        r.addProperty("height", w.height);
        r.add("settings", w.settings);
        return r;
    }

    private static void applySettings(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        long t0 = System.nanoTime();
        try {
            JsonObject settings = JsonParser.parseString(body).getAsJsonObject();
            world = new LabWorld(server, template, settings, versions.incrementAndGet(), genPool);
        } catch (Exception e) {
            JsonObject r = new JsonObject();
            r.addProperty("error", e.getMessage() == null ? e.toString() : e.getMessage());
            json(ex, 400, r);
            return;
        }
        JsonObject r = state();
        r.addProperty("buildMs", (System.nanoTime() - t0) / 1e6);
        json(ex, 200, r);
    }

    /** A grid of (TILE+2)^2 sample columns starting one sample before the tile, so the viewer can mesh tile edges. */
    private static void tile(HttpExchange ex, Map<String, String> q) throws Exception {
        LabWorld w = world;
        int v = Integer.parseInt(q.getOrDefault("v", "0"));
        if (v != w.version) {
            ex.sendResponseHeaders(409, -1);
            return;
        }
        int tx = Integer.parseInt(q.get("tx")), tz = Integer.parseInt(q.get("tz")), step = Integer.parseInt(q.get("step"));
        int n = TILE + 2;
        int originX = tx * TILE * step - step, originZ = tz * TILE * step - step;
        long t0 = System.nanoTime();

        // Every chunk that holds a sample, started together so the generation pool works on them in parallel.
        Map<Long, CompletableFuture<LabWorld.Columns>> needed = new HashMap<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int x = originX + j * step, z = originZ + i * step;
                int cx = x >> 4, cz = z >> 4;
                needed.computeIfAbsent(((long) cx << 32) ^ (cz & 0xFFFFFFFFL), k -> w.chunk(cx, cz));
            }
        }
        CompletableFuture.allOf(needed.values().toArray(CompletableFuture[]::new)).join();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 16);
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(0x57474C31);
            out.writeInt(w.version);
            out.writeInt(tx);
            out.writeInt(tz);
            out.writeInt(step);
            out.writeInt(n);
            out.writeInt(originX);
            out.writeInt(originZ);
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    int x = originX + j * step, z = originZ + i * step;
                    LabWorld.Columns c = needed.get(((long) (x >> 4) << 32) ^ ((z >> 4) & 0xFFFFFFFFL)).join();
                    int col = (z & 15) * 16 + (x & 15);
                    int from = c.start[col], to = Math.min(c.start[col + 1], from + 255);
                    out.writeByte(to - from);
                    for (int r = from; r < to; r++) {
                        out.writeShort(c.y0[r]);
                        out.writeShort(c.y1[r]);
                        out.writeInt(c.colour[r]);
                    }
                }
            }
        }
        byte[] body = bytes.toByteArray();
        ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().add("Content-Encoding", "gzip");
        ex.getResponseHeaders().add("X-Gen-Ms", String.valueOf((System.nanoTime() - t0) / 1_000_000));
        ex.getResponseHeaders().add("X-Chunks", String.valueOf(needed.size()));
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    /**
     * Compares the lab's chunk with the world's chunk at the same spot, block by block. Blocks the lab never produces
     * (trees, ores, flowers: anything from features) are counted separately, since the lab doesn't run features.
     */
    private static void compare(HttpExchange ex, Map<String, String> q) throws Exception {
        int cx = Integer.parseInt(q.get("cx")), cz = Integer.parseInt(q.get("cz"));
        // mode=stage checks the lab's machinery, so it uses the settings embedded in the template (the world's own),
        // not whatever is being edited.
        LabWorld w = "stage".equals(q.get("mode")) ? new LabWorld(server, template, templateSettings(), -1, genPool) : world;
        ProtoChunk lab = CompletableFuture.supplyAsync(() -> w.generateChunk(cx, cz), genPool).join();
        // mode=stage: the world's own generator through the same steps as the lab (should match exactly).
        // Otherwise: the world's saved chunk, which also has carvers, features and structures.
        ChunkAccess real = "stage".equals(q.get("mode"))
                ? CompletableFuture.supplyAsync(() -> w.generateWithWorldGenerator(server, cx, cz), genPool).join()
                : server.submit(() -> server.overworld().getChunk(cx, cz)).join();
        java.util.Set<BlockState> labStates = new java.util.HashSet<>();
        int minY = Math.max(lab.getMinBuildHeight(), real.getMinBuildHeight());
        int maxY = Math.min(lab.getMaxBuildHeight(), real.getMaxBuildHeight());
        net.minecraft.core.BlockPos.MutableBlockPos p = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int y = minY; y < maxY; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            labStates.add(lab.getBlockState(p.set(x, y, z)));
        }
        long same = 0, differ = 0, featureBlocks = 0, labSolidRealAir = 0, labAirRealSolid = 0;
        Map<String, Integer> examples = new HashMap<>();
        for (int y = minY; y < maxY; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockState a = lab.getBlockState(p.set(cx * 16 + x, y, cz * 16 + z));
            BlockState b = real.getBlockState(p);
            if (a == b) { same++; continue; }
            if (!labStates.contains(b)) { featureBlocks++; continue; }
            differ++;
            if (!a.isAir() && b.isAir()) labSolidRealAir++;
            if (a.isAir() && !b.isAir()) labAirRealSolid++;
            examples.merge(a.getBlock().getDescriptionId() + " -> " + b.getBlock().getDescriptionId(), 1, Integer::sum);
        }
        JsonObject r = new JsonObject();
        r.addProperty("same", same);
        r.addProperty("differ", differ);
        r.addProperty("featureBlocks", featureBlocks);
        r.addProperty("labSolidRealAir", labSolidRealAir);
        r.addProperty("labAirRealSolid", labAirRealSolid);
        List<Map.Entry<String, Integer>> top = new ArrayList<>(examples.entrySet());
        top.sort((e1, e2) -> e2.getValue() - e1.getValue());
        JsonObject ex2 = new JsonObject();
        for (int i = 0; i < Math.min(12, top.size()); i++) ex2.addProperty(top.get(i).getKey(), top.get(i).getValue());
        r.add("examples", ex2);
        json(ex, 200, r);
    }

    /**
     * A fast overview: n x n samples every `step` blocks from (x0, z0), each the highest span's top, bottom and water
     * level as floats (NaN where there's no land), plus how many spans overlap there. Uses the shape maths directly,
     * without generating chunks, so it can cover many kilometres in seconds. Surface blocks aren't known at this level.
     */
    /** Holds the benchmark area loaded until fullgen removes it. */
    private static final TicketType<ChunkPos> BENCH = TicketType.create("worldgenlab_bench", Comparator.comparingLong(ChunkPos::toLong));

    /**
     * Generates the (2r+1)^2 chunks around (cx, cz) to FULL through the server's own chunk system and reports the time.
     * This includes features, structures and light, which the tile endpoint leaves out. Each chunk gets its own ticket at
     * the FULL level, like a chunk at the edge of a player's view distance: loaded and sent, but not ticking. (One region
     * ticket over the area would make the inner chunks tick their mobs and blocks, which a flying player doesn't cause.)
     * Point it at an area nothing has generated yet; the chunks are saved to the real world.
     */
    private static void fullgen(HttpExchange ex, Map<String, String> q) throws Exception {
        int cx = Integer.parseInt(q.get("cx")), cz = Integer.parseInt(q.get("cz"));
        int r = Math.max(0, Math.min(32, Integer.parseInt(q.getOrDefault("r", "16"))));
        ServerLevel level = server.overworld();
        int total = (2 * r + 1) * (2 * r + 1);
        long t0 = System.nanoTime(), deadline = t0 + 900_000_000_000L;
        server.submit(() -> {
            for (int x = cx - r; x <= cx + r; x++)
                for (int z = cz - r; z <= cz + r; z++) level.getChunkSource().addRegionTicket(BENCH, new ChunkPos(x, z), 0, new ChunkPos(x, z));
        }).join();
        int done;
        while (true) {
            done = server.submit(() -> {
                int n = 0;
                for (int x = cx - r; x <= cx + r; x++)
                    for (int z = cz - r; z <= cz + r; z++)
                        if (level.getChunkSource().getChunkNow(x, z) != null) n++;
                return n;
            }).join();
            if (done == total || System.nanoTime() > deadline) break;
            Thread.sleep(100);
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        server.submit(() -> {
            for (int x = cx - r; x <= cx + r; x++)
                for (int z = cz - r; z <= cz + r; z++) level.getChunkSource().removeRegionTicket(BENCH, new ChunkPos(x, z), 0, new ChunkPos(x, z));
        }).join();
        JsonObject res = new JsonObject();
        res.addProperty("chunks", total);
        res.addProperty("done", done);
        res.addProperty("seconds", seconds);
        res.addProperty("chunksPerSecond", done / seconds);
        json(ex, 200, res);
    }

    /**
     * Generates the n x n chunks from (cx, cz) through the world's own generator (biomes, noise and surface) twice, with
     * skyislands' FastGen shortcuts on and then off, and counts blocks that differ. Terrain isn't blended around
     * structures unless structures=1, which uses the real world's structures and roads. Run it while nothing else is
     * generating: the switch is global.
     */
    private static void fastcheck(HttpExchange ex, Map<String, String> q) throws Exception {
        int cx = Integer.parseInt(q.get("cx")), cz = Integer.parseInt(q.get("cz"));
        int n = Math.max(1, Math.min(32, Integer.parseInt(q.getOrDefault("n", "8"))));
        boolean structures = "1".equals(q.get("structures"));
        LabWorld w = world;
        long differ = 0, blocks = 0, water = 0, solid = 0, onNanos = 0, offNanos = 0, quarts = 0, biomesDiffer = 0;
        long skipped0 = skyislands.FastGen.CELLS_SKIPPED.get(), filled0 = skyislands.FastGen.CELLS_FILLED.get();
        Map<String, Integer> examples = new HashMap<>();
        try {
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
                int x = cx + i, z = cz + j;
                skyislands.FastGen.setEnabled(true);
                long t0 = System.nanoTime();
                ProtoChunk on = CompletableFuture.supplyAsync(() -> w.generateWithWorldGenerator(server, x, z, structures), genPool).join();
                long t1 = System.nanoTime();
                skyislands.FastGen.setEnabled(false);
                ProtoChunk off = CompletableFuture.supplyAsync(() -> w.generateWithWorldGenerator(server, x, z, structures), genPool).join();
                long t2 = System.nanoTime();
                onNanos += t1 - t0;
                offNanos += t2 - t1;
                net.minecraft.core.BlockPos.MutableBlockPos p = new net.minecraft.core.BlockPos.MutableBlockPos();
                for (int y = on.getMinBuildHeight(); y < on.getMaxBuildHeight(); y++) for (int bz = 0; bz < 16; bz++) for (int bx = 0; bx < 16; bx++) {
                    BlockState a = on.getBlockState(p.set(x * 16 + bx, y, z * 16 + bz)), b = off.getBlockState(p);
                    blocks++;
                    if (!b.isAir()) solid++;
                    if (!b.getFluidState().isEmpty()) water++;
                    if (a != b) {
                        differ++;
                        examples.merge(b.getBlock().getDescriptionId() + " -> " + a.getBlock().getDescriptionId(), 1, Integer::sum);
                    }
                }
                // Every biome sample of the chunk, too: the sky-biome shortcut must give the same biomes.
                for (int qy = on.getMinBuildHeight() >> 2; qy < on.getMaxBuildHeight() >> 2; qy++)
                    for (int lx = 0; lx < 4; lx++) for (int lz = 0; lz < 4; lz++) {
                        quarts++;
                        var ba = on.getNoiseBiome((x << 2) + lx, qy, (z << 2) + lz);
                        var bb = off.getNoiseBiome((x << 2) + lx, qy, (z << 2) + lz);
                        if (!ba.equals(bb)) {
                            biomesDiffer++;
                            examples.merge("biome y=" + (qy << 2) + " " + bb.getRegisteredName() + " -> " + ba.getRegisteredName(), 1, Integer::sum);
                        }
                    }
            }
        } finally {
            skyislands.FastGen.setEnabled(true);
        }
        JsonObject r = new JsonObject();
        r.addProperty("chunks", n * n);
        r.addProperty("blocks", blocks);
        r.addProperty("nonAirBlocks", solid);
        r.addProperty("waterBlocks", water);
        r.addProperty("differ", differ);
        r.addProperty("biomeSamples", quarts);
        r.addProperty("biomesDiffer", biomesDiffer);
        r.addProperty("msPerChunkOn", onNanos / 1e6 / (n * n));
        r.addProperty("msPerChunkOff", offNanos / 1e6 / (n * n));
        r.addProperty("cellsSkipped", skyislands.FastGen.CELLS_SKIPPED.get() - skipped0);
        r.addProperty("cellsFilled", skyislands.FastGen.CELLS_FILLED.get() - filled0);
        JsonObject ex2 = new JsonObject();
        examples.entrySet().stream().sorted((e1, e2) -> e2.getValue() - e1.getValue()).limit(12)
                .forEach(e -> ex2.addProperty(e.getKey(), e.getValue()));
        r.add("examples", ex2);
        r.add("counters", fastCounters());
        json(ex, 200, r);
    }

    /**
     * What structure blending (Beardifier) the real world gives the chunks from (cx, cz) to (cx + n - 1, cz + n - 1): for
     * each chunk with anything to blend, the size of every list the Beardifier holds and skyislands' blend reach boxes.
     */
    private static void beard(HttpExchange ex, Map<String, String> q) throws Exception {
        int cx = Integer.parseInt(q.get("cx")), cz = Integer.parseInt(q.get("cz"));
        int n = Math.max(1, Math.min(32, Integer.parseInt(q.getOrDefault("n", "1"))));
        JsonObject r = new JsonObject();
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            var pos = new net.minecraft.world.level.ChunkPos(cx + i, cz + j);
            var b = CompletableFuture.supplyAsync(() -> net.minecraft.world.level.levelgen.Beardifier.forStructuresInChunk(server.overworld().structureManager(), pos), genPool).join();
            JsonObject c = new JsonObject();
            boolean any = false;
            for (Class<?> k = b.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(b);
                    int size = -1;
                    if (v instanceof java.util.Collection<?> col) size = col.size();
                    else if (v instanceof java.util.ListIterator<?> it) { int back = 0; while (it.hasPrevious()) { it.previous(); back++; } int all = 0; while (it.hasNext()) { it.next(); all++; } for (int m = all; m > back; m--) it.previous(); size = all; }
                    c.addProperty(f.getName(), v == null ? "null" : size >= 0 ? String.valueOf(size) : v.getClass().getSimpleName());
                    if (size > 0) any = true;
                }
            }
            var proto = CompletableFuture.supplyAsync(() -> server.overworld().getChunk(pos.x, pos.z, net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_REFERENCES), genPool).join();
            StringBuilder starts = new StringBuilder(), refs = new StringBuilder();
            var reg = server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
            proto.getAllStarts().forEach((st, start) -> {
                if (!start.isValid()) return;
                starts.append(reg.getKey(st)).append(" pieces=").append(start.getPieces().size()).append(" box=").append(start.getBoundingBox());
                try {
                    var f = st.getClass().getField("enhancedTerrainAdaptation");
                    Object a = f.get(st);
                    starts.append(" adaptation=").append(a.getClass().getSimpleName()).append(" kernel=").append(a.getClass().getMethod("getKernelSize").invoke(a));
                } catch (ReflectiveOperationException ignored) {}
                start.getPieces().stream().limit(3).forEach(pc -> starts.append(" [").append(pc.getClass().getSimpleName())
                        .append(pc instanceof net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece pe ? " " + pe.getElement().getClass().getSimpleName() + " " + pe.getElement().getProjection() : "")
                        .append(' ').append(pc.getBoundingBox()).append(']'));
                starts.append(' ');
            });
            proto.getAllReferences().forEach((st, set) -> { if (!set.isEmpty()) refs.append(reg.getKey(st)).append('x').append(set.size()).append(' '); });
            c.addProperty("starts", starts.toString().trim());
            c.addProperty("references", refs.toString().trim());
            if (starts.length() > 0 || refs.length() > 0) any = true;
            Map<String, Long> before = new HashMap<>();
            skyislands.BlendReach.KINDS.forEach((k, v) -> before.put(k, v.get()));
            int[] reach = skyislands.BlendReach.of(b, pos);
            StringBuilder kinds = new StringBuilder();
            skyislands.BlendReach.KINDS.forEach((k, v) -> { long d = v.get() - before.getOrDefault(k, 0L); if (d > 0) kinds.append(k).append('x').append(d).append(' '); });
            c.addProperty("kinds", kinds.toString().trim());
            c.addProperty("reach", reach == null ? "unknown" : java.util.Arrays.toString(reach));
            if (any || reach == null || reach.length > 0) r.add(pos.x + "," + pos.z, c);
        }
        json(ex, 200, r);
    }

    /**
     * In the real world's chunks within r of (cx, cz) (generated in full if missing): water source blocks, and how many
     * have air beside them (a water wall, which worldgen water doesn't flow over) or air under them (hanging water).
     * Lists up to 20 places.
     */
    private static void waterwalls(HttpExchange ex, Map<String, String> q) throws Exception {
        int cx = Integer.parseInt(q.get("cx")), cz = Integer.parseInt(q.get("cz"));
        int r = Math.max(0, Math.min(32, Integer.parseInt(q.getOrDefault("r", "8"))));
        long[] n = new long[7];
        com.google.gson.JsonArray where = new com.google.gson.JsonArray();
        skyislands.SkyTerrain[] found = new skyislands.SkyTerrain[1];
        server.overworld().getChunkSource().randomState().router().fluidLevelFloodednessNoise().mapAll(df -> {
            if (df instanceof skyislands.SkyTerrain t) found[0] = t;
            return df;
        });
        skyislands.SkyTerrain terrain = found[0];
        for (int x = cx - r; x <= cx + r; x++) for (int z = cz - r; z <= cz + r; z++) {
            int fx = x, fz = z;
            server.submit(() -> {
                var level = server.overworld();
                var chunk = level.getChunk(fx, fz);
                var p = new net.minecraft.core.BlockPos.MutableBlockPos();
                var nb = new net.minecraft.core.BlockPos.MutableBlockPos();
                var sections = chunk.getSections();
                for (int si = 0; si < sections.length; si++) {
                    var sec = sections[si];
                    if (sec.hasOnlyAir() || !sec.getStates().maybeHas(st -> st.getFluidState().isSource())) continue;
                    int y0 = chunk.getSectionYFromSectionIndex(si) << 4;
                    for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                        var st = sec.getBlockState(lx, ly, lz);
                        var fl = st.getFluidState();
                        if (!fl.isSource() || !fl.is(net.minecraft.tags.FluidTags.WATER)) continue;
                        n[0]++;
                        p.set(fx * 16 + lx, y0 + ly, fz * 16 + lz);
                        boolean wall = false;
                        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) if (level.getBlockState(nb.setWithOffset(p, d)).isAir()) wall = true;
                        boolean hang = level.getBlockState(nb.setWithOffset(p, net.minecraft.core.Direction.DOWN)).isAir();
                        if (wall) n[1]++;
                        if (hang) n[2]++;
                        if (wall || hang) {
                            // Lake water from skyislands if the column has a layer whose water level is above this block.
                            double[] sp = terrain == null ? new double[0] : terrain.spansAt(p.getX(), p.getZ());
                            String kind = "other";
                            for (int k = 0; k < sp.length; k += 3) if (!Double.isNaN(sp[k + 2]) && p.getY() < sp[k + 2] && p.getY() > sp[k] - 40) kind = "lake";
                            if (kind.equals("lake")) n[wall ? 3 : 4]++;
                            // Open to the sky: nothing but this water column above it.
                            boolean surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, p.getX(), p.getZ()) <= p.getY() + 4;
                            if (surface) n[wall ? 5 : 6]++;
                            if (where.size() < 30) where.add(p.getX() + " " + p.getY() + " " + p.getZ() + (wall ? " wall" : "") + (hang ? " hang" : "") + " " + kind + " spans=" + java.util.Arrays.toString(java.util.Arrays.stream(sp).map(v -> Math.round(v * 10) / 10.0).toArray()));
                        }
                    }
                }
                return null;
            }).join();
        }
        JsonObject res = new JsonObject();
        res.addProperty("chunks", (2 * r + 1) * (2 * r + 1));
        res.addProperty("waterSources", n[0]);
        res.addProperty("besideAir", n[1]);
        res.addProperty("overAir", n[2]);
        res.addProperty("lakeBesideAir", n[3]);
        res.addProperty("lakeOverAirOnly", n[4]);
        res.addProperty("surfaceBesideAir", n[5]);
        res.addProperty("surfaceOverAirOnly", n[6]);
        res.add("examples", where);
        json(ex, 200, res);
    }

    private static JsonObject fastCounters() {
        JsonObject c = new JsonObject();
        c.addProperty("beardSkipped", skyislands.FastGen.BEARD_SKIPPED.get());
        c.addProperty("beardBounded", skyislands.FastGen.BEARD_BOUNDED.get());
        c.addProperty("beardKept", skyislands.FastGen.BEARD_KEPT.get());
        c.addProperty("beardCellsZeroed", skyislands.FastGen.BEARD_CELLS_ZEROED.get());
        JsonObject kinds = new JsonObject();
        skyislands.BlendReach.KINDS.forEach((k, v) -> kinds.addProperty(k, v.get()));
        c.add("blendReachKinds", kinds);
        c.addProperty("cellsSkipped", skyislands.FastGen.CELLS_SKIPPED.get());
        c.addProperty("cellsFilled", skyislands.FastGen.CELLS_FILLED.get());
        c.addProperty("oresSkipped", skyislands.FastGen.ORES_SKIPPED.get());
        c.addProperty("sectionsDropped", skyislands.FastGen.SECTIONS_DROPPED.get());
        c.addProperty("biomesSkipped", skyislands.FastGen.BIOMES_SKIPPED.get());
        c.addProperty("biomesExact", skyislands.FastGen.BIOMES_EXACT.get());
        return c;
    }

    /**
     * For each chunk within r of (cx, cz) in the real world (generating any that are missing), one hash per block column
     * over every block state from the bottom of the world to the top, in x-major chunk order and z-major column order.
     * Comparing two worlds' hashes shows which columns came out differently.
     */
    private static void colhash(HttpExchange ex, Map<String, String> q) throws Exception {
        int cx = Integer.parseInt(q.get("cx")), cz = Integer.parseInt(q.get("cz"));
        int r = Math.max(0, Math.min(32, Integer.parseInt(q.getOrDefault("r", "16"))));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            for (int x = cx - r; x <= cx + r; x++) for (int z = cz - r; z <= cz + r; z++) {
                int fx = x, fz = z;
                int[] h = server.submit(() -> {
                    ChunkAccess c = server.overworld().getChunk(fx, fz);
                    int[] out2 = new int[256];
                    net.minecraft.core.BlockPos.MutableBlockPos p = new net.minecraft.core.BlockPos.MutableBlockPos();
                    for (int bz = 0; bz < 16; bz++) for (int bx = 0; bx < 16; bx++) {
                        int hh = 1;
                        for (int y = c.getMinBuildHeight(); y < c.getMaxBuildHeight(); y++) {
                            hh = 31 * hh + net.minecraft.world.level.block.Block.getId(c.getBlockState(p.set(fx * 16 + bx, y, fz * 16 + bz)));
                        }
                        out2[bz * 16 + bx] = hh;
                    }
                    return out2;
                }).join();
                for (int v : h) out.writeInt(v);
            }
        }
        byte[] body = bytes.toByteArray();
        ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static void map(HttpExchange ex, Map<String, String> q) throws Exception {
        LabWorld w = world;
        int x0 = Integer.parseInt(q.get("x0")), z0 = Integer.parseInt(q.get("z0"));
        int n = Math.min(1024, Integer.parseInt(q.get("n"))), step = Integer.parseInt(q.get("step"));
        skyislands.SkyTerrain t = w.terrain();
        float[] top = new float[n * n], bottom = new float[n * n], water = new float[n * n];
        byte[] count = new byte[n * n];
        List<CompletableFuture<Void>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int row = i;
            rows.add(CompletableFuture.runAsync(() -> {
                for (int j = 0; j < n; j++) {
                    double[] s = t.spansAt(x0 + j * step, z0 + row * step);
                    int k = row * n + j, best = -1;
                    for (int a = 0; a < s.length; a += 3) if (s[a] > s[a + 1] && (best < 0 || s[a] > s[best])) best = a;
                    count[k] = (byte) Math.min(127, s.length / 3);
                    top[k] = best < 0 ? Float.NaN : (float) s[best];
                    bottom[k] = best < 0 ? Float.NaN : (float) s[best + 1];
                    water[k] = best < 0 ? Float.NaN : (float) s[best + 2];
                }
            }, genPool));
        }
        CompletableFuture.allOf(rows.toArray(CompletableFuture[]::new)).join();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(n * n * 13);
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(n);
            for (int k = 0; k < n * n; k++) {
                out.writeFloat(top[k]);
                out.writeFloat(bottom[k]);
                out.writeFloat(water[k]);
                out.writeByte(count[k]);
            }
        }
        byte[] body = bytes.toByteArray();
        ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().add("Content-Encoding", "gzip");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    /** The sky-island settings the template (the world's datapack) was generated with. */
    private static JsonObject templateSettings() {
        return template.getAsJsonObject("noise_router").getAsJsonObject("fluid_level_floodedness").getAsJsonObject("settings");
    }

    /** Calderas, atolls and plateau regions in an area, so the viewer can list them and jump there. */
    private static void features(HttpExchange ex, Map<String, String> q) throws IOException {
        LabWorld w = world;
        int x0 = Integer.parseInt(q.get("x0")), z0 = Integer.parseInt(q.get("z0"));
        int x1 = Integer.parseInt(q.get("x1")), z1 = Integer.parseInt(q.get("z1"));
        skyislands.SkyTerrain t = w.terrain();
        JsonObject r = new JsonObject();
        com.google.gson.JsonArray basins = new com.google.gson.JsonArray();
        com.google.gson.JsonArray plateaus = new com.google.gson.JsonArray();
        if (t != null) {
            for (double[] b : t.basinsIn(x0, z0, x1, z1)) {
                JsonObject o = new JsonObject();
                o.addProperty("x", (int) b[0]);
                o.addProperty("z", (int) b[1]);
                o.addProperty("radius", Math.round(b[2]));
                o.addProperty("level", (int) b[3]);
                o.addProperty("type", b[4] > 0 ? "atoll" : "caldera");
                o.addProperty("layer", (int) b[5]);
                basins.add(o);
            }
            // Plateau cores: grid points deep inside a plateau region, at least 1500 blocks apart.
            List<int[]> picked = new ArrayList<>();
            for (int x = x0; x <= x1; x += 256) for (int z = z0; z <= z1; z += 256) {
                if (t.plateauWeight(x, z) < 0.95) continue;
                boolean near = false;
                for (int[] p : picked) near |= Math.hypot(p[0] - x, p[1] - z) < 1500;
                if (near) continue;
                picked.add(new int[]{x, z});
                JsonObject o = new JsonObject();
                o.addProperty("x", x);
                o.addProperty("z", z);
                plateaus.add(o);
            }
        }
        com.google.gson.JsonArray islands = new com.google.gson.JsonArray();
        if (t != null) {
            String[] kinds = {"plain island", "lake island", "peak island", "stepped island"};
            for (double[] is : t.islandsIn(x0, z0, x1, z1)) {
                if (is[4] == 0) continue;
                JsonObject o = new JsonObject();
                o.addProperty("x", (int) is[0]);
                o.addProperty("z", (int) is[1]);
                o.addProperty("radius", Math.round(is[2]));
                o.addProperty("level", (int) is[3]);
                o.addProperty("type", kinds[(int) is[4]]);
                islands.add(o);
            }
        }
        r.add("basins", basins);
        r.add("plateaus", plateaus);
        r.add("islands", islands);
        json(ex, 200, r);
    }

    private static void serveFile(HttpExchange ex, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        Path f = webDir.resolve(path.substring(1)).normalize();
        if (!f.startsWith(webDir) || !Files.isRegularFile(f)) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        String type = path.endsWith(".html") ? "text/html; charset=utf-8" : path.endsWith(".js") ? "text/javascript"
                : path.endsWith(".css") ? "text/css" : "application/octet-stream";
        byte[] body = Files.readAllBytes(f);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static void json(HttpExchange ex, int status, JsonObject body) throws IOException {
        byte[] b = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> m = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(part.substring(0, i), java.net.URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }
}
