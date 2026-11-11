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
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Mods ship structure templates saved by older Minecraft versions. The first time a template from a given old version
 * is loaded, DataFixerUpper builds its upgrade rules for that version, which takes seconds, and ModernFix's dynamic_dfu
 * (on by default) leaves that until first use. In practice it lands during chunk generation: a teleport into fresh land
 * on 2026-11-11 froze the server for 35 seconds, about 90% of it building these rules.
 *
 * After the server starts, a low-priority background thread reads every structure template's data version and runs one
 * template per old version through the upgrade, so the rules exist before anyone needs them. Templates aren't kept, so
 * this costs no memory. Turn it off with -Dskyislands.dfuWarmup=false.
 */
public final class DfuWarmup {
    private static final Logger LOG = LogUtils.getLogger();

    private DfuWarmup() {}

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
        Set<Integer> warmed = new TreeSet<>();
        int read = 0, failed = 0;
        Map<ResourceLocation, Resource> templates = server.getResourceManager().listResources("structure", id -> id.getPath().endsWith(".nbt"));
        for (Map.Entry<ResourceLocation, Resource> e : templates.entrySet()) {
            if (server.isStopped()) return;
            try (InputStream in = e.getValue().open()) {
                CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
                read++;
                // The same default StructureTemplateManager.readStructure uses for templates without a version.
                int version = NbtUtils.getDataVersion(tag, 500);
                if (version >= current || !warmed.add(version)) continue;
                DataFixTypes.STRUCTURE.updateToCurrentVersion(server.getFixerUpper(), tag, version);
            } catch (Exception ex) {
                failed++;
            }
        }
        LOG.info("[skyislands] Warmed structure upgrades for {} old data versions {} from {} templates ({} unreadable) in {} s",
                warmed.size(), warmed, read, failed, String.format("%.1f", (System.nanoTime() - t0) / 1e9));
    }
}
