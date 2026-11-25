package skyislands.mixin;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import skyislands.LakeMonuments;
import skyislands.SkyTerrain;
import skyislands.StructureFootings;

/**
 * Keeps structures off rivers in sky-island worlds: a structure whose start lands within RIVER_MARGIN blocks of a
 * river's channel isn't placed there. Structures are placed after the terrain and would otherwise sit across the
 * channel and cut the river off. This is the check that generation, /locate and explorer maps all go through, so they
 * agree on where structures are.
 */
@Mixin(Structure.class)
abstract class StructureMixin {
    private static final double RIVER_MARGIN = 20.0;

    /**
     * While a structure's place is checked or its pieces are laid out, columns with no land answer with a made-up ground
     * height (StructureFootings.searchHeight). Generation, /locate and explorer maps all check through here.
     */
    @Inject(method = "findValidGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void skyislands$searching(Structure.GenerationContext ctx, CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        // An ocean monument's place in a sky-island world is the terrain's to give (LakeMonuments): there is no ocean
        // biome for the game's own checks to find, and a monument belongs in a lake, on the river that leaves it or not.
        if ((Object) this instanceof OceanMonumentStructure) {
            SkyTerrain t = SkyTerrain.of(ctx.randomState());
            if (t != null) {
                cir.setReturnValue(LakeMonuments.generationPoint(t, ctx));
                return;
            }
        }
        StructureFootings.enterSearch();
    }

    /** An ocean monument's biome list reads as every biome; where one may stand is decided above and below (LakeMonuments). */
    @Inject(method = "biomes", at = @At("RETURN"), cancellable = true)
    private void skyislands$monumentBiomes(CallbackInfoReturnable<HolderSet<Biome>> cir) {
        if ((Object) this instanceof OceanMonumentStructure) {
            HolderSet<Biome> any = LakeMonuments.anyBiome();
            if (any != null) cir.setReturnValue(any);
        }
    }

    @Inject(method = "generate", at = @At("HEAD"))
    private void skyislands$planning(CallbackInfoReturnable<StructureStart> cir) {
        StructureFootings.enterSearch();
    }

    @Inject(method = "generate", at = @At("RETURN"))
    private void skyislands$planned(CallbackInfoReturnable<StructureStart> cir) {
        StructureFootings.exitSearch();
    }

    @Inject(method = "findValidGenerationPoint", at = @At("RETURN"), cancellable = true)
    private void skyislands$offRivers(Structure.GenerationContext ctx, CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        StructureFootings.exitSearch();
        Optional<Structure.GenerationStub> stub = cir.getReturnValue();
        if (stub.isEmpty()) return;
        SkyTerrain t = SkyTerrain.of(ctx.randomState());
        BlockPos p = stub.get().position();
        if (t == null) {
            // A monument's biome list reads as every biome (skyislands$monumentBiomes), so in a world without sky
            // islands the game's rule for where one stands is kept here.
            if ((Object) this instanceof OceanMonumentStructure && !LakeMonuments.inDeepOcean(ctx, p)) cir.setReturnValue(Optional.empty());
            return;
        }
        if (t.nearRiver(p.getX(), p.getZ(), RIVER_MARGIN)) cir.setReturnValue(Optional.empty());
    }
}
