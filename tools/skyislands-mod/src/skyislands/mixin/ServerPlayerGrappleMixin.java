package skyislands.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import skyislands.GrappleLanding;

/**
 * Caps a grappling-hook player's fall distance on landing at what their real vertical speed justifies
 * (skyislands.GrappleLanding). doCheckFallDamage is where the server applies fall damage for a move that reports
 * landing; the cap only applies within a few ticks of the hook mod's last movement update for this player.
 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerGrappleMixin implements GrappleLanding.Tracked {
    @Unique private int skyislands$tick = Integer.MIN_VALUE;
    @Unique private int skyislands$prevTick = Integer.MIN_VALUE;
    @Unique private double skyislands$y;
    @Unique private double skyislands$prevY;

    @Override
    public void skyislands$grappleUpdate(int tick, double y) {
        skyislands$prevTick = skyislands$tick;
        skyislands$prevY = skyislands$y;
        skyislands$tick = tick;
        skyislands$y = y;
    }

    @Override
    public double skyislands$grappleVerticalSpeed(int tick) {
        if (tick - skyislands$tick > GrappleLanding.RECENT_TICKS || skyislands$prevTick == Integer.MIN_VALUE) return Double.NaN;
        int dt = Math.max(1, skyislands$tick - skyislands$prevTick);
        return (skyislands$y - skyislands$prevY) / dt;
    }

    @Inject(method = "doCheckFallDamage", at = @At("HEAD"))
    private void skyislands$capGrappleLanding(double dx, double dy, double dz, boolean onGround, CallbackInfo ci) {
        if (!onGround) return;
        ServerPlayer self = (ServerPlayer) (Object) this;
        double vy = skyislands$grappleVerticalSpeed(self.server.getTickCount());
        if (Double.isNaN(vy)) return;
        // This move's own drop counts too: it can be the last part of the descent.
        float cap = GrappleLanding.fallDistanceFor(Math.min(vy, dy));
        if (self.fallDistance > cap) self.fallDistance = cap;
    }
}
