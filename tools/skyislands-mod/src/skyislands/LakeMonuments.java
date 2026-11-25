package skyislands;

import com.mojang.logging.LogUtils;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.registries.holdersets.AnyHolderSet;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

/**
 * Ocean monuments in a sky-island world, which has no ocean. The game puts a monument where the biome is deep ocean,
 * at a fixed height (y 39 to 61), full of water up to the world's sea level; here there is no ocean biome, the islands
 * are far above that height, and the sea level is the bottom of the world. So in a sky-island overworld the terrain
 * says where monuments go (SkyTerrain.MonumentSite: the deep open water of a few lake islands), and four things change:
 *
 * 1. Where the game looks. It tries one chunk per cell of the monuments' placement grid, picked at random. In a cell
 *    with a monument lake it is given that lake's chunk instead (RandomSpreadStructurePlacementMixin), so /locate and
 *    explorer maps, which walk the same grid, find the monument where it generates.
 * 2. Whether it may go there. The terrain's answer replaces the biome checks (StructureMixin).
 * 3. Its height. The monument is built by the game's own code and then moved down from y 39 so that its top is 2
 *    blocks under the lake's surface, as it is under the sea (sinkTo).
 * 4. Its water. Its pieces fill themselves with water below "sea level" and air above; they are given the lake's water
 *    level for it (OceanMonumentPieceMixin, MonumentBuildingMixin).
 *
 * The structure stays minecraft:monument, with the game's guardians, elder guardians, sponges and gold.
 *
 * One more thing has to give way: the monument's list of biomes (deep oceans). The game drops a structure set from a
 * world whose biome source has none of its biomes, and Structure Essentials' fast structure lookup, which /locate and
 * explorer maps go through, skips every chunk whose biome isn't on the structure's list before it asks whether the
 * structure can generate there. So a monument's list reads as every biome (anyBiome, through StructureMixin). In a
 * world without sky islands the game's own rule still holds, deep ocean where the monument stands, because
 * StructureMixin checks it there (inDeepOcean).
 *
 * A monument is saved as its outer piece alone, and the game builds the rooms again from the seed each time the
 * structure is loaded, at y 39 (OceanMonumentStructure.regeneratePiecesAfterLoad). keepHeight moves that rebuilt
 * monument to the saved piece's height, so chunks generated after a restart continue the same monument.
 */
public final class LakeMonuments {
    private static final Logger LOG = LogUtils.getLogger();
    /** The height the game builds every monument at. */
    private static final int GAME_BASE = 39;

    private LakeMonuments() {}

    /** One piece of a monument (the building or a room): mixed into OceanMonumentPieces.OceanMonumentPiece. */
    public interface Piece {
        /** Moves the piece by dy and gives it the water level it fills itself to. */
        void skyislands$sink(int dy, int seaLevel);

        /** The water level the piece was given, or `fallback` (the world's sea level) if it was never moved. */
        int skyislands$seaLevel(int fallback);
    }

    /** A whole monument: mixed into OceanMonumentPieces.MonumentBuilding. */
    public interface Building {
        /** Moves the building and its rooms so its lowest block is at `base`, in water up to `seaLevel`. */
        void skyislands$sinkTo(int base, int seaLevel);
    }

    /** The monuments' placement grid: mixed into RandomSpreadStructurePlacement. */
    public interface Grid {
        void skyislands$monumentLakes(SkyTerrain terrain);
    }

    private static MinecraftServer anyBiomeServer;
    private static HolderSet<Biome> anyBiome;

    /** Every biome: what an ocean monument's biome list reads as while a server runs, or null before there is one. */
    public static HolderSet<Biome> anyBiome() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        // One server per run on a dedicated server; a single-player game starts a new one per world.
        if (server != anyBiomeServer) {
            anyBiome = new AnyHolderSet<>(server.registryAccess().lookupOrThrow(Registries.BIOME));
            anyBiomeServer = server;
        }
        return anyBiome;
    }

    /** The game's own biome rule for where a monument stands, for worlds without sky islands. */
    public static boolean inDeepOcean(Structure.GenerationContext ctx, BlockPos pos) {
        return ctx.chunkGenerator().getBiomeSource().getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()),
                QuartPos.fromBlock(pos.getZ()), ctx.randomState().sampler()).is(BiomeTags.HAS_OCEAN_MONUMENT);
    }

    /**
     * The overworld's terrain, while its lakes hold monuments. Only the overworld gets them: another dimension can
     * reuse the overworld's noise settings (Excessive Utilities' quantum quarry does), and would otherwise grow a copy
     * of every monument where nobody goes.
     */
    private static volatile SkyTerrain overworld;

    /** Hands the overworld's terrain to the monuments' placement grid, before any chunk is generated. */
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        overworld = null;
        if (!FastGen.isSky(level.getChunkSource().getGenerator())) return;
        SkyTerrain terrain = SkyTerrain.of(level.getChunkSource().randomState());
        if (terrain == null || !terrain.hasMonuments()) return;
        StructureSet set = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).get(BuiltinStructureSets.OCEAN_MONUMENTS);
        if (set != null && set.placement() instanceof RandomSpreadStructurePlacement grid) {
            overworld = terrain;
            ((Grid) grid).skyislands$monumentLakes(terrain);
            LOG.info("[skyislands] Ocean monuments generate in lakes in the sky-island overworld.");
        } else {
            LOG.warn("[skyislands] No ocean monuments in lakes: the minecraft:ocean_monuments structure set is missing or isn't a random spread.");
        }
    }

    /** A monument's place and pieces at this chunk, if the terrain is the overworld's and has a monument lake centred on it. */
    public static Optional<Structure.GenerationStub> generationPoint(SkyTerrain terrain, Structure.GenerationContext ctx) {
        if (terrain != overworld) return Optional.empty();
        ChunkPos chunk = ctx.chunkPos();
        SkyTerrain.MonumentSite site = terrain.monumentSiteAt(chunk.x, chunk.z);
        if (site == null) return Optional.empty();
        int base = site.level() - SkyTerrain.MONUMENT_BELOW;
        return Optional.of(new Structure.GenerationStub(new BlockPos(chunk.getMinBlockX(), base, chunk.getMinBlockZ()), builder -> {
            // As OceanMonumentStructure.createTopPiece builds it: centred on the chunk's corner, facing a random way.
            WorldgenRandom random = ctx.random();
            Direction facing = Direction.Plane.HORIZONTAL.getRandomDirection(random);
            OceanMonumentPieces.MonumentBuilding building =
                    new OceanMonumentPieces.MonumentBuilding(random, chunk.getMinBlockX() - 29, chunk.getMinBlockZ() - 29, facing);
            ((Building) building).skyislands$sinkTo(base, site.level());
            builder.addPiece(building);
        }));
    }

    /**
     * Called with a monument's saved pieces and the monument the game has just rebuilt from them at y 39. A saved
     * piece at another height is a lake's monument (SkyTerrain never puts one at y 39), and the rebuilt one goes back
     * to that height and water level.
     */
    public static void keepHeight(PiecesContainer saved, PiecesContainer rebuilt) {
        if (saved.isEmpty() || rebuilt.isEmpty()) return;
        int base = saved.pieces().get(0).getBoundingBox().minY();
        if (base != GAME_BASE && rebuilt.pieces().get(0) instanceof Building building) {
            building.skyislands$sinkTo(base, base + SkyTerrain.MONUMENT_BELOW);
        }
    }
}
