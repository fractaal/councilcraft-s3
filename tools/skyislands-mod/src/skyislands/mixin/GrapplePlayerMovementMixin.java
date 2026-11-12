package skyislands.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import skyislands.GrappleLanding;

/**
 * The Grappling Hook mod's movement update, as the server applies it: once it has moved the player to the position the
 * client sent, note that height and tick for the landing cap (skyislands.GrappleLanding). Optional: without the hook
 * mod this mixin doesn't apply and nothing else changes.
 */
@Pseudo
@Mixin(targets = "com.yyon.grapplinghook.network.serverbound.PlayerMovementC2SPayload")
abstract class GrapplePlayerMovementMixin {
    @Inject(method = "lambda$process$0(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At(value = "INVOKE", target = "Lcom/yyon/grapplinghook/util/Vec;applyAsPositionTo(Lnet/minecraft/world/entity/Entity;)V", shift = At.Shift.AFTER))
    private void skyislands$noteHeight(ServerPlayer player, CallbackInfo ci) {
        if (player instanceof GrappleLanding.Tracked t) {
            t.skyislands$grappleUpdate(player.server.getTickCount(), player.getY());
            GrappleLanding.logFirstUpdate();
        }
    }
}
