package skyislands.mixin;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import skyislands.SkyTerrain;

/**
 * Keeps structures off rivers in sky-island worlds: a structure whose start lands within RIVER_MARGIN blocks of a
 * river's channel isn't placed there. Structures are placed after the terrain and would otherwise sit across the
 * channel and cut the river off. This is the check that generation, /locate and explorer maps all go through, so they
 * agree on where structures are.
 */
@Mixin(Structure.class)
abstract class StructureMixin {
    private static final double RIVER_MARGIN = 20.0;

    @Inject(method = "findValidGenerationPoint", at = @At("RETURN"), cancellable = true)
    private void skyislands$offRivers(Structure.GenerationContext ctx, CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        Optional<Structure.GenerationStub> stub = cir.getReturnValue();
        if (stub.isEmpty()) return;
        SkyTerrain t = SkyTerrain.of(ctx.randomState());
        if (t == null) return;
        BlockPos p = stub.get().position();
        if (t.nearRiver(p.getX(), p.getZ(), RIVER_MARGIN)) cir.setReturnValue(Optional.empty());
    }
}
