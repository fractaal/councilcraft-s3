package skyislands.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import skyislands.LakeMonuments;

/**
 * When a saved monument is loaded, the game builds it again from the seed at the height it gives every monument. A
 * monument in a lake goes back to the height it was saved at (skyislands.LakeMonuments.keepHeight).
 */
@Mixin(OceanMonumentStructure.class)
abstract class OceanMonumentStructureMixin {
    @ModifyReturnValue(method = "regeneratePiecesAfterLoad", at = @At("RETURN"))
    private static PiecesContainer skyislands$keepHeight(PiecesContainer rebuilt, ChunkPos chunk, long seed, PiecesContainer saved) {
        LakeMonuments.keepHeight(saved, rebuilt);
        return rebuilt;
    }
}
