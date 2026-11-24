package skyislands.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import skyislands.WhaleBurst;

/**
 * Sky Whales' WhaleCorpseSpawner.spawnCorpse works out every block of a dead whale's corpse (a map from position to
 * block) and then places them one by one before making them a Sable sub-level. Just before the first block is placed,
 * give the whale's killer those blocks' loot instead and stop: nothing is placed and no sub-level is made. See
 * skyislands.WhaleBurst. When skyislands.mixin.SkyWhaleDeathMixin hasn't noted a dying whale, or the loot can't be
 * rolled, the corpse is built as before.
 * Optional: without Sky Whales this mixin doesn't apply.
 */
@Pseudo
@Mixin(targets = "dev.dacite.skywhales.corpse.WhaleCorpseSpawner")
abstract class WhaleCorpseBurstMixin {
    @Inject(method = "spawnCorpse", remap = false, cancellable = true,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private static void skyislands$lootInsteadOfCorpse(ServerLevel level, Vec3 center, float deathYaw, CallbackInfo ci,
                                                       @Local Map<BlockPos, BlockState> corpse) {
        if (WhaleBurst.dying != null && WhaleBurst.give(level, WhaleBurst.dying, corpse)) ci.cancel();
    }
}
