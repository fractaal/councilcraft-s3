package skyislands.mixin;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import skyislands.DenserPlacement;
import skyislands.LakeMonuments;
import skyislands.SkyTerrain;

/**
 * Lets skyislands.StructureDensity give a structure set a tighter grid. Placement (getPotentialStructureChunk) and
 * everything that searches for structures (/locate, explorer maps, which call spacing() and separation()) see the same
 * values, so a structure is found where it generates.
 */
@Mixin(RandomSpreadStructurePlacement.class)
abstract class RandomSpreadStructurePlacementMixin implements DenserPlacement, LakeMonuments.Grid {
    @Shadow @Final private int spacing;
    @Shadow @Final private int separation;

    @Unique private boolean skyislands$scaled;
    @Unique private int skyislands$spacing, skyislands$separation;
    /** Set on the ocean monuments' grid in a sky-island world: the terrain that says which lakes hold a monument. */
    @Unique private SkyTerrain skyislands$monumentLakes;

    @Override
    public void skyislands$setGrid(int spacing, int separation) {
        skyislands$spacing = spacing;
        skyislands$separation = separation;
        skyislands$scaled = true;
    }

    @Override
    public int skyislands$baseSpacing() { return spacing; }

    @Override
    public int skyislands$baseSeparation() { return separation; }

    @Override
    public void skyislands$monumentLakes(SkyTerrain terrain) { skyislands$monumentLakes = terrain; }

    /**
     * The chunk the game tries in a grid cell is picked at random, which all but never lands in a lake with room for a
     * monument. A cell that has a monument lake gives that lake's chunk instead (skyislands.LakeMonuments); every other
     * cell gives the game's own pick, where no monument can generate.
     */
    @Inject(method = "getPotentialStructureChunk", at = @At("HEAD"), cancellable = true)
    private void skyislands$monumentChunk(long seed, int chunkX, int chunkZ, CallbackInfoReturnable<ChunkPos> cir) {
        SkyTerrain terrain = skyislands$monumentLakes;
        if (terrain == null) return;
        int grid = skyislands$scaled ? skyislands$spacing : spacing;
        SkyTerrain.MonumentSite site = terrain.monumentInCell(Math.floorDiv(chunkX, grid), Math.floorDiv(chunkZ, grid), grid);
        if (site != null) cir.setReturnValue(new ChunkPos(site.chunkX(), site.chunkZ()));
    }

    @Redirect(method = "getPotentialStructureChunk", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadStructurePlacement;spacing:I", opcode = Opcodes.GETFIELD))
    private int skyislands$spacingField(RandomSpreadStructurePlacement self) {
        return skyislands$scaled ? skyislands$spacing : spacing;
    }

    @Redirect(method = "getPotentialStructureChunk", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadStructurePlacement;separation:I", opcode = Opcodes.GETFIELD))
    private int skyislands$separationField(RandomSpreadStructurePlacement self) {
        return skyislands$scaled ? skyislands$separation : separation;
    }

    @Inject(method = "spacing", at = @At("HEAD"), cancellable = true)
    private void skyislands$spacing(CallbackInfoReturnable<Integer> cir) {
        if (skyislands$scaled) cir.setReturnValue(skyislands$spacing);
    }

    @Inject(method = "separation", at = @At("HEAD"), cancellable = true)
    private void skyislands$separation(CallbackInfoReturnable<Integer> cir) {
        if (skyislands$scaled) cir.setReturnValue(skyislands$separation);
    }
}
