package skyislands.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import skyislands.LakeMonuments;

/**
 * Lets a monument's pieces stand in a lake (skyislands.LakeMonuments): each can be moved down from the height the game
 * builds it at, and fills itself with water up to the lake's level instead of the world's sea level, which in a
 * sky-island world is the bottom of the world. A piece that was never moved does what the game does.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces$OceanMonumentPiece")
abstract class OceanMonumentPieceMixin implements LakeMonuments.Piece {
    @Unique private static final int skyislands$UNSET = Integer.MIN_VALUE;
    @Unique private int skyislands$sea = skyislands$UNSET;

    @Override
    public void skyislands$sink(int dy, int seaLevel) {
        ((StructurePiece) (Object) this).getBoundingBox().move(0, dy, 0);
        skyislands$sea = seaLevel;
    }

    @Override
    public int skyislands$seaLevel(int fallback) {
        return skyislands$sea == skyislands$UNSET ? fallback : skyislands$sea;
    }

    @ModifyExpressionValue(method = "generateWaterBox", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getSeaLevel()I"))
    private int skyislands$lakeLevel(int seaLevel) {
        return skyislands$seaLevel(seaLevel);
    }
}
