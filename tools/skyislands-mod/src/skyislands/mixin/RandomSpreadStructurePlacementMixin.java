package skyislands.mixin;

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

/**
 * Lets skyislands.StructureDensity give a structure set a tighter grid. Placement (getPotentialStructureChunk) and
 * everything that searches for structures (/locate, explorer maps, which call spacing() and separation()) see the same
 * values, so a structure is found where it generates.
 */
@Mixin(RandomSpreadStructurePlacement.class)
abstract class RandomSpreadStructurePlacementMixin implements DenserPlacement {
    @Shadow @Final private int spacing;
    @Shadow @Final private int separation;

    @Unique private boolean skyislands$scaled;
    @Unique private int skyislands$spacing, skyislands$separation;

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
