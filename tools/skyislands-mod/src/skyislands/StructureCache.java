package skyislands;

import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.datafix.DataFixTypes;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * A disk cache of structure templates already upgraded from older Minecraft versions. Upgrading a template with
 * DataFixerUpper takes from milliseconds to seconds, and it happens the first time each template is used after every
 * restart, in the middle of chunk generation. With the cache, a template is upgraded once and later loads read the
 * upgraded copy.
 *
 * Entries are keyed by a SHA-256 of the original template and its data version. The folder name holds the game's data
 * version and a hash of every loaded mod and its version, because mods can add their own data fixers: any mod change
 * starts a fresh folder. Turn it off with -Dskyislands.structureCache=false; delete the folder to rebuild it.
 */
public final class StructureCache {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int CURRENT = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    private static volatile Path dir;

    private StructureCache() {}

    /** Called once the server folder is known. */
    public static void init(Path serverDir) {
        if ("false".equals(System.getProperty("skyislands.structureCache"))) return;
        String mods = ModList.get().getMods().stream().map(m -> m.getModId() + ":" + m.getVersion())
                .sorted().collect(Collectors.joining(","));
        Path d = serverDir.resolve("skyislands-cache").resolve("structures-" + CURRENT + "-" + sha256(mods.getBytes()).substring(0, 12));
        try {
            Files.createDirectories(d);
            dir = d;
            LOG.info("[skyislands] Upgraded structure template cache: {}", d);
        } catch (IOException e) {
            LOG.warn("[skyislands] Can't create the structure template cache at {}, templates upgrade as usual", d, e);
        }
    }

    /** DataFixTypes.updateToCurrentVersion for a structure template, through the cache. */
    public static CompoundTag upgrade(DataFixTypes type, DataFixer fixer, CompoundTag tag, int version) {
        Path d = dir;
        if (d == null || version >= CURRENT) return type.updateToCurrentVersion(fixer, tag, version);
        Path f = entry(d, tag, version);
        if (f != null && Files.isRegularFile(f)) {
            try {
                return NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
            } catch (IOException e) {
                LOG.warn("[skyislands] Unreadable cache entry {}, upgrading again", f);
            }
        }
        CompoundTag out = type.updateToCurrentVersion(fixer, tag, version);
        if (f != null) write(f, out);
        return out;
    }

    /** True if a template with this content and version is already in the cache (or needs no upgrade). */
    public static boolean cached(CompoundTag tag, int version) {
        Path d = dir;
        if (d == null) return true;
        if (version >= CURRENT) return true;
        Path f = entry(d, tag, version);
        return f == null || Files.isRegularFile(f);
    }

    public static boolean enabled() { return dir != null; }

    private static Path entry(Path d, CompoundTag tag, int version) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                NbtIo.write(tag, out);
                out.writeInt(version);
            }
            String key = sha256(bytes.toByteArray());
            return d.resolve(key.substring(0, 2)).resolve(key + ".nbt");
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(Path f, CompoundTag out) {
        try {
            Files.createDirectories(f.getParent());
            Path tmp = Files.createTempFile(f.getParent(), "tmp", ".nbt");
            NbtIo.writeCompressed(out, tmp);
            Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.debug("[skyislands] Couldn't write cache entry {}", f, e);
        }
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
