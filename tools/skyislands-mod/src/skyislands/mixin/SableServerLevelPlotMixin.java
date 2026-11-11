package skyislands.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LightChunkGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Sable builds each sub-level's light engine with the light layers the level's own engine has, by checking that engine's
 * blockEngine and skyEngine fields. ScalableLux replaces those engines and leaves both fields null, so sub-levels would
 * get no light (Sable issue #1638). Take the layers from the dimension instead, as vanilla's ChunkMap does: block light
 * always, sky light if the dimension has it. Without ScalableLux this gives the same values Sable already used.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot")
abstract class SableServerLevelPlotMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;<init>(Lnet/minecraft/world/level/chunk/LightChunkGetter;ZZ)V"), index = 1)
    private boolean skyislands$blockLight(LightChunkGetter getter, boolean block, boolean sky) {
        return true;
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;<init>(Lnet/minecraft/world/level/chunk/LightChunkGetter;ZZ)V"), index = 2)
    private boolean skyislands$skyLight(LightChunkGetter getter, boolean block, boolean sky) {
        return getter.getLevel() instanceof Level level ? level.dimensionType().hasSkyLight() : sky;
    }
}
