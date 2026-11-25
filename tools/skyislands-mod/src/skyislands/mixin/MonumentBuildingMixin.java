package skyislands.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.List;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import skyislands.LakeMonuments;

/**
 * A whole monument moved into a lake (skyislands.LakeMonuments). Its rooms are pieces of their own that only the
 * building knows about, each placed in the world when the building was constructed, so they move with it.
 */
@Mixin(OceanMonumentPieces.MonumentBuilding.class)
abstract class MonumentBuildingMixin implements LakeMonuments.Building {
    // Declared as a list of OceanMonumentPiece, which only the game's own package can name.
    @Shadow @Final private List<StructurePiece> childPieces;

    @Override
    public void skyislands$sinkTo(int base, int seaLevel) {
        int dy = base - ((StructurePiece) (Object) this).getBoundingBox().minY();
        ((LakeMonuments.Piece) this).skyislands$sink(dy, seaLevel);
        for (StructurePiece room : childPieces) ((LakeMonuments.Piece) room).skyislands$sink(dy, seaLevel);
    }

    /** The box of water the building clears for itself reaches up to the sea level. */
    @ModifyExpressionValue(method = "postProcess", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getSeaLevel()I"))
    private int skyislands$lakeLevel(int seaLevel) {
        return ((LakeMonuments.Piece) this).skyislands$seaLevel(seaLevel);
    }
}
