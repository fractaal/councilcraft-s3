package skyislands.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import skyislands.GrappleLanding;

/**
 * The hook catching on something, on the server: just before the hook mod tells the player's client that the hook has
 * attached, stop a player who is in a void fall (skyislands.GrappleLanding.stopVoidFall). The client builds its rope
 * physics from the player's motion when that message arrives, so the motion reset has to reach the client first.
 * Optional: without the hook mod this mixin doesn't apply.
 */
@Pseudo
@Mixin(targets = "com.yyon.grapplinghook.content.entity.grapplinghook.GrapplinghookEntity")
abstract class GrappleHookAttachMixin {
    @Shadow(remap = false) public net.minecraft.world.entity.Entity shootingEntity;

    @Inject(method = "serverAttach", remap = false,
            at = @At(value = "INVOKE", ordinal = 0, remap = false,
                    target = "Lcom/yyon/grapplinghook/util/GrappleModUtils;sendToCorrectClient(Lcom/yyon/grapplinghook/network/S2CPayload;ILnet/minecraft/world/level/Level;)V"))
    private void skyislands$stopVoidFall(CallbackInfo ci) {
        if (shootingEntity instanceof ServerPlayer player) GrappleLanding.stopVoidFall(player);
    }
}
