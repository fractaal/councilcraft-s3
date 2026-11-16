package skyislands.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Create: Coasters Simulated 0.1.5 looks for its cart and rivet blocks by reading all 4096 cells of every non-empty
 * chunk section of every Sable sub-level, on each physics step, whether or not the sub-level has anything to do with
 * coasters. On the live server that scan took most of the main thread whenever many sub-levels were loaded (2026-11-16).
 * A section whose palette doesn't list the block can't contain it, so skip those sections the way the scan already
 * skips all-air ones. The result is the same; only sections that might hold the block are read cell by cell.
 * Optional: without the coaster mod this mixin doesn't apply.
 */
@Pseudo
@Mixin(targets = "dev.silvergold.simulatedcoasters.track.cart.CoasterCartPlotScan")
abstract class CoasterPlotScanMixin {
    @WrapOperation(method = {"scanBearingCells", "forEachBearingWorldCell"}, remap = false,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;hasOnlyAir()Z", remap = false))
    private static boolean skyislands$skipSectionsWithoutBlock(LevelChunkSection section, Operation<Boolean> hasOnlyAir,
                                                               @Local(argsOnly = true) Block block) {
        return hasOnlyAir.call(section) || !section.maybeHas(state -> state.is(block));
    }
}
