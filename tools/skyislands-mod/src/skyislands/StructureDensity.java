package skyislands;

import com.mojang.logging.LogUtils;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;

/**
 * Sky-island worlds have far less ground than a normal overworld, so most structure placements land on nothing. This
 * packs overworld structures closer together to make up for it: every structure set that can generate in the sky-island
 * overworld and not in the Nether or the End gets a grid whose cells hold `skyislands.structureDensity` (default 2)
 * times as many attempts. Spacing and separation shrink by the square root, so the minimum gap between two of a
 * structure shrinks with them. Strongholds (concentric rings) and Nether and End structures are left alone. Mod
 * dimensions are not consulted: some (mining dimensions) reuse overworld biomes, which would rule out every set, and an
 * overworld structure set generating more often there too is harmless.
 *
 * It runs when the overworld loads, before any chunk is generated, from the structure sets as all datapacks left them.
 * The grid only matters for land that is still to be generated: chunks already generated keep their structures.
 */
public final class StructureDensity {
    private static final Logger LOG = LogUtils.getLogger();
    private static final double DENSITY = Double.parseDouble(System.getProperty("skyislands.structureDensity", "2"));
    private static boolean applied;

    private StructureDensity() {}

    public static void onLevelLoad(LevelEvent.Load event) {
        if (applied || !(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (DENSITY == 1.0 || !FastGen.isSky(level.getChunkSource().getGenerator())) return;
        applied = true;
        var access = level.getServer().registryAccess();
        Set<Holder<Biome>> overworld = level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
        Set<Holder<Biome>> elsewhere = new HashSet<>();
        Registry<LevelStem> stems = access.registryOrThrow(Registries.LEVEL_STEM);
        for (var key : java.util.List.of(LevelStem.NETHER, LevelStem.END)) {
            LevelStem stem = stems.get(key);
            if (stem != null) elsewhere.addAll(stem.generator().getBiomeSource().possibleBiomes());
        }
        double shrink = Math.sqrt(DENSITY);
        int changed = 0, skipped = 0;
        StringBuilder examples = new StringBuilder();
        for (Map.Entry<net.minecraft.resources.ResourceKey<StructureSet>, StructureSet> e : access.registryOrThrow(Registries.STRUCTURE_SET).entrySet()) {
            StructureSet set = e.getValue();
            if (!(set.placement() instanceof RandomSpreadStructurePlacement rs)) continue;
            boolean here = false, there = false;
            for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                var biomes = entry.structure().value().biomes();
                for (Holder<Biome> b : overworld) if (biomes.contains(b)) { here = true; break; }
                for (Holder<Biome> b : elsewhere) if (biomes.contains(b)) { there = true; break; }
            }
            if (!here || there) { if (here) skipped++; continue; }
            DenserPlacement d = (DenserPlacement) rs;
            int spacing = Math.max(1, (int) Math.round(d.skyislands$baseSpacing() / shrink));
            int separation = Math.max(0, Math.min(spacing - 1, (int) Math.round(d.skyislands$baseSeparation() / shrink)));
            d.skyislands$setGrid(spacing, separation);
            changed++;
            if (examples.length() < 400) examples.append(e.getKey().location()).append(' ')
                    .append(d.skyislands$baseSpacing()).append('/').append(d.skyislands$baseSeparation()).append("->")
                    .append(spacing).append('/').append(separation).append(", ");
        }
        LOG.info("[skyislands] Structure density x{} in the sky-island overworld: {} structure sets packed closer, {} left alone because they also generate in the Nether or the End. {}",
                DENSITY, changed, skipped, examples);
    }
}
