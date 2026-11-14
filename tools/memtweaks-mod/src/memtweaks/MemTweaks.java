package memtweaks;

import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.Unit;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Client-only memory settings for the pack:
 * the size of Voxy's cache of recently used world sections, which Voxy hard-codes (1024, or 2048 when the
 * heap is 3.8 GiB or more) and never shrinks; sharing the duplicate in-memory copies of bundled jars;
 * keeping other mods' entity textures out of the block atlas; and sharing identical faces between block models.
 */
@Mod(value = MemTweaks.MODID, dist = Dist.CLIENT)
public final class MemTweaks {
    public static final String MODID = "memtweaks";
    private static final Logger LOG = LogManager.getLogger("memtweaks");

    /** memtweaks-client.toml. The pack doesn't ship this file, so a player's own value survives pack updates. */
    static final ModConfigSpec SPEC;
    static final ModConfigSpec.IntValue VOXY_SECTION_CACHE;
    static final ModConfigSpec.BooleanValue SHARE_NESTED_JARS;
    static final ModConfigSpec.BooleanValue ENTITY_TEXTURES_OUT_OF_BLOCK_ATLAS;
    static final ModConfigSpec.BooleanValue SHARE_IDENTICAL_QUADS;
    static final ModConfigSpec.BooleanValue REUSE_MODEL_LOCATION_LIST;
    static final ModConfigSpec.BooleanValue LAZY_MODEL_REPLACE_ALL;
    static final ModConfigSpec.BooleanValue LAZY_MODEL_FILE_ENTRIES;
    static final ModConfigSpec.BooleanValue TRUST_EMF_FOLDER_LISTING;
    static final ModConfigSpec.BooleanValue STARTUP_BANNER;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        VOXY_SECTION_CACHE = b.comment(
                "How many recently used world sections Voxy keeps in memory after it is done with them.",
                "Each one is about 262 KB of heap, so 1024 is about 270 MB and 2048 about 540 MB.",
                "Voxy's own choice is 2048. Larger means fewer reloads from disk when you turn back; smaller saves memory.",
                "Read when you join a world, so rejoin after changing it.")
                .defineInRange("voxySectionCacheSize", 1024, 64, 65536);
        SHARE_NESTED_JARS = b.comment(
                "Jars bundled inside other mods' jars are held in memory up to three times over.",
                "This keeps one copy of each and frees the rest (about 350 MB in this pack). Applies at the next launch.")
                .define("shareNestedJars", true);
        ENTITY_TEXTURES_OUT_OF_BLOCK_ATLAS = b.comment(
                "The Grappling Hook mod's atlas file pulls every mod's entity textures into the block atlas,",
                "which doubles it to 16384x8192. This keeps only that mod's own hook and rope textures there.",
                "Turn it off if something that used to render shows the purple and black missing texture.",
                "Applies at the next resource reload (F3+T) or launch.")
                .define("entityTexturesOutOfBlockAtlas", true);
        SHARE_IDENTICAL_QUADS = b.comment(
                "Block models for different states of a block mostly repeat the same faces. This keeps one copy of each",
                "distinct face instead of one per model (about 0.3 GB in this pack).",
                "Turn it off if blocks render with wrong or missing faces. Applies to models built after the change.")
                .define("shareIdenticalQuads", true);
        REUSE_MODEL_LOCATION_LIST = b.comment(
                "While resources load, Puzzles Lib builds a list of every block state's model once for each mod that uses",
                "its model events (Diagonal Fences, Walls and Windows, Easy Anvils, Easy Magic, Visual Workbench).",
                "This builds it once and reuses it for the others, which shortens startup and every F3+T by a few seconds.",
                "Turn it off if models from those mods look wrong.")
                .define("reuseModelLocationList", true);
        LAZY_MODEL_REPLACE_ALL = b.comment(
                "Some mods wrap their models by asking for every model in the game to be replaced (Create Thrusters,",
                "Fetzi's Asian Deco and Display Delight in this pack). With ModernFix that walks every model location and",
                "bakes and keeps each wrapped model during startup. This applies the wrapping when a model is first baked",
                "instead, which shortens startup and every F3+T by several seconds and keeps those models out of memory",
                "until they are needed. Turn it off if those mods' blocks or items render wrong.")
                .define("lazyModelReplaceAll", true);
        LAZY_MODEL_FILE_ENTRIES = b.comment(
                "More Axolotl Variants API loops over every model file on each resource load, and with ModernFix that",
                "parses every model file in the game (about four seconds in this pack). This makes such a loop free.",
                "Turn it off if axolotl bucket items show the missing model.")
                .define("lazyModelFileEntries", true);
        TRUST_EMF_FOLDER_LISTING = b.comment(
                "Entity Model Features lists its model folders once per resource load, then still asks every mod pack",
                "about each model file it didn't find there (about two seconds per load). This takes the listing's word",
                "for it. Turn it off if a custom entity model from a resource pack stops showing.")
                .define("trustEmfFolderListing", true);
        STARTUP_BANNER = b.comment(
                "Shows \"fractalWARP STARTUP BOOST 9000 ACTIVE\" on the first loading screen, and how long the start took on",
                "the menu for a few seconds afterwards, when the pack's startup agent (mods/startupfix) is active.",
                "It does nothing else.")
                .define("startupBanner", true);
        SPEC = b.build();
    }

    public MemTweaks(IEventBus modBus, ModContainer container) {
        dropTraceLogEvents();
        container.registerConfig(ModConfig.Type.CLIENT, SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // Once when loading finishes, and again on joining a world to catch jars first opened after that.
        modBus.addListener(MemTweaks::onLoadComplete);
        NeoForge.EVENT_BUS.addListener(MemTweaks::onWorldJoin);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ScreenEvent.Render.Post e) -> StartupBanner.renderStat(e.getGuiGraphics()));
        modBus.addListener(MemTweaks::onRegisterReloadListeners);
    }

    /**
     * NeoForge's logging setup accepts every event that carries one of its markers, whatever its level, and
     * its loader logs a TRACE line with such a marker for each event sent to each mod. No log file keeps
     * those lines, but each one is still built and queued first, which costs several seconds of startup on
     * the threads everything else waits for. This puts a filter in front that drops TRACE events before
     * they are created. -Dmemtweaks.keepTraceLogs=true leaves logging alone.
     */
    private static void dropTraceLogEvents() {
        if (Boolean.getBoolean("memtweaks.keepTraceLogs")) return;
        try {
            org.apache.logging.log4j.core.LoggerContext context = (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
            org.apache.logging.log4j.core.config.Configuration config = context.getConfiguration();
            org.apache.logging.log4j.core.Filter existing = config.getFilter();
            org.apache.logging.log4j.core.Filter noTrace = org.apache.logging.log4j.core.filter.ThresholdFilter.createFilter(
                    org.apache.logging.log4j.Level.DEBUG, org.apache.logging.log4j.core.Filter.Result.NEUTRAL, org.apache.logging.log4j.core.Filter.Result.DENY);
            noTrace.start();
            if (existing != null) config.removeFilter(existing);
            config.addFilter(noTrace);
            if (existing != null) config.addFilter(existing);
            context.updateLoggers();
            LOG.info("Logging: TRACE events are dropped before they are created");
        } catch (Throwable e) {
            LOG.warn("Logging: left as it was ({})", e.toString());
        }
    }

    private static void onLoadComplete(FMLLoadCompleteEvent event) { shareNestedJars("load complete"); }

    private static void onWorldJoin(ClientPlayerNetworkEvent.LoggingIn event) {
        shareNestedJars("world join");
        LOG.info("Baked quads at world join: {}", QuadShare.stats());
    }

    private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        // Clear when a reload starts, before its models are built, so models built during and after it still share.
        event.registerReloadListener((PreparableReloadListener) (barrier, resources, prepareProfiler, applyProfiler, backgroundExecutor, gameExecutor) -> {
            QuadShare.clear();
            return barrier.wait(Unit.INSTANCE).thenRunAsync(() -> { }, gameExecutor);
        });
    }

    private static void shareNestedJars(String when) {
        Thread t = new Thread(() -> {
            try {
                if (!SHARE_NESTED_JARS.get()) return;
                // The mods' own file systems, in case their providers aren't among the JVM's installed ones.
                List<FileSystemProvider> providers = new ArrayList<>();
                ModList.get().getModFiles().forEach(f -> providers.add(f.getFile().getSecureJar().getRootPath().getFileSystem().provider()));
                JarShare.Result r = JarShare.run(providers);
                LOG.info("Nested jars at {}: {} held in memory, {} duplicate copies shared, {} MB freed",
                        when, r.inMemoryJars(), r.shared(), r.bytesFreed() / 1_000_000);
            } catch (Throwable e) {
                LOG.warn("Nested jars at {}: left untouched ({})", when, e.toString());
            }
        }, "memtweaks-jar-share");
        t.setDaemon(true);
        t.start();
    }

    /** Called from the model hook. Before the config has loaded, the default (on) applies. */
    public static boolean shareIdenticalQuads() {
        try {
            return SHARE_IDENTICAL_QUADS.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Called from the atlas hook. Before the config has loaded, the default (on) applies. */
    public static boolean limitEntityAtlasSource() {
        try {
            return ENTITY_TEXTURES_OUT_OF_BLOCK_ATLAS.get();
        } catch (RuntimeException e) {
            LOG.warn("Block atlas: config not readable yet ({}), using the default", e.toString());
            return true;
        }
    }

    /** Called from the Puzzles Lib hook. Before the config has loaded, the default (on) applies. */
    public static boolean reuseModelLocationList() {
        try {
            return REUSE_MODEL_LOCATION_LIST.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Called from the ModernFix hook. Before the config has loaded, the default (on) applies. */
    public static boolean lazyModelReplaceAll() {
        try {
            return LAZY_MODEL_REPLACE_ALL.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Called when a model bakery is created, which on the first load is before the config has loaded: default on. */
    public static boolean lazyModelFileEntries() {
        try {
            return LAZY_MODEL_FILE_ENTRIES.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Called from the Entity Model Features hook. Before the config has loaded, the default (on) applies. */
    public static boolean trustEmfFolderListing() {
        try {
            return TRUST_EMF_FOLDER_LISTING.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Called while the first loading screen is drawn, which is before the config has loaded: default on. */
    public static boolean startupBanner() {
        try {
            return STARTUP_BANNER.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static void logLazyModelReplace(int calls, int replacedNow) {
        LOG.info("Model replaceAll: {} calls deferred to bake time, {} existing models replaced right away", calls, replacedNow);
    }

    public static void warnLazyModelReplace(String what, Throwable e) {
        LOG.warn("Model replaceAll: {} ({})", what, e.toString());
    }

    public static void logModelLocationShare(int size, int reused) {
        LOG.info("Model location list: {} entries built once, reused {} times by Puzzles Lib in this model event", size, reused);
    }

    public static void logEntityAtlasSource(int kept) {
        LOG.info("Block atlas: the whole-folder 'entity' source is limited to the Grappling Hook mod's own {} textures", kept);
    }

    /** Called from the Voxy hook with the size Voxy picked; returns the size to use instead. */
    public static int voxySectionCacheSize(int voxyChoice) {
        try {
            int size = VOXY_SECTION_CACHE.get();
            LOG.info("Voxy section cache: {} sections (Voxy picked {})", size, voxyChoice);
            return size;
        } catch (RuntimeException e) {
            // The config isn't loaded yet; leave Voxy's own choice alone.
            LOG.warn("Voxy section cache: config not readable ({}), keeping Voxy's {}", e.toString(), voxyChoice);
            return voxyChoice;
        }
    }
}
