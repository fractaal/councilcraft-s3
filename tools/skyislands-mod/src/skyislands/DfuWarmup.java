package skyislands;

import com.mojang.logging.LogUtils;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.datafix.DataFixTypes;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Mods ship structure templates saved by older Minecraft versions, and upgrading one with DataFixerUpper happens the
 * first time it is used, in the middle of chunk generation. A teleport into fresh land on 2026-11-11 froze the server
 * for 35 seconds, about 90% of it upgrading templates. Two passes on a low-priority background thread after the server
 * starts take that work out of play:
 * 1. One template per old data version, so DataFixerUpper builds its upgrade rules for every version (seconds each).
 * 2. Every template that isn't in skyislands.StructureCache yet is upgraded into it, so later loads during generation
 *    read the upgraded copy. The first boot with a new mod list does the whole pack; later boots only check hashes.
 * A few cached entries are also upgraded again and compared, as a check that the cache returns the same result.
 * Turn it off with -Dskyislands.dfuWarmup=false.
 */
public final class DfuWarmup {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int CHECKS = 25;

    private DfuWarmup() {}

    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        StructureCache.init(event.getServer().getServerDirectory());
    }

    public static void onServerStarted(ServerStartedEvent event) {
        if ("false".equals(System.getProperty("skyislands.dfuWarmup"))) return;
        MinecraftServer server = event.getServer();
        Thread t = new Thread(() -> warm(server), "skyislands-dfu-warmup");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void warm(MinecraftServer server) {
        long t0 = System.nanoTime();
        int current = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        Map<ResourceLocation, Resource> templates = server.getResourceManager().listResources("structure", id -> id.getPath().endsWith(".nbt"));
        // Pass 1: upgrade rules for every old data version.
        Set<Integer> warmed = new TreeSet<>();
        for (Resource r : templates.values()) {
            if (server.isStopped()) return;
            CompoundTag tag = read(r);
            if (tag == null) continue;
            int version = NbtUtils.getDataVersion(tag, 500);
            if (version < current && warmed.add(version)) DataFixTypes.STRUCTURE.updateToCurrentVersion(server.getFixerUpper(), tag, version);
        }
        long t1 = System.nanoTime();
        LOG.info("[skyislands] Warmed structure upgrade rules for {} old data versions in {} s", warmed.size(), String.format("%.1f", (t1 - t0) / 1e9));
        if (!StructureCache.enabled()) return;
        // Pass 2: fill the cache, and check a few entries that were already there.
        int old = 0, added = 0, checked = 0, mismatched = 0, failed = 0;
        for (Resource r : templates.values()) {
            if (server.isStopped()) return;
            CompoundTag tag = read(r);
            if (tag == null) { failed++; continue; }
            int version = NbtUtils.getDataVersion(tag, 500);
            if (version >= current) continue;
            old++;
            try {
                if (!StructureCache.cached(tag, version)) {
                    StructureCache.upgrade(DataFixTypes.STRUCTURE, server.getFixerUpper(), tag, version);
                    added++;
                } else if (checked < CHECKS) {
                    checked++;
                    CompoundTag fresh = DataFixTypes.STRUCTURE.updateToCurrentVersion(server.getFixerUpper(), tag.copy(), version);
                    if (!fresh.equals(StructureCache.upgrade(DataFixTypes.STRUCTURE, server.getFixerUpper(), tag, version))) mismatched++;
                }
            } catch (Exception e) {
                failed++;
            }
        }
        LOG.info("[skyislands] Structure template cache: {} templates, {} from older versions, {} newly upgraded into the cache, {} unreadable, {} cached entries re-checked ({} differed), in {} s",
                templates.size(), old, added, failed, checked, mismatched, String.format("%.1f", (System.nanoTime() - t1) / 1e9));
    }

    private static CompoundTag read(Resource r) {
        try (InputStream in = r.open()) {
            return NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        } catch (Exception e) {
            return null;
        }
    }
}
