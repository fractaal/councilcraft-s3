package skyislands.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Create: Coasters Simulated 0.1.5 calls onCartRemoved for every Sable sub-level that isn't a coaster cart, on each
 * physics step, and onCartRemoved ends by sending every player in the dimension a "this cart has no links" packet. With
 * airships and other contraptions loaded that was thousands of identical packets a second from the main thread
 * (2026-11-16). Send that closing packet for a given sub-level at most once every RESEND_TICKS game ticks.
 *
 * Nothing depends on the repeats: real link changes are sent by linkCarts and unlinkPair themselves, and a player who
 * joins or starts tracking a sub-level gets its links from the mod's own full sync. The rest of onCartRemoved still
 * runs every time, so the mod's own bookkeeping is unchanged.
 * Optional: without the coaster mod this mixin doesn't apply.
 */
@Pseudo
@Mixin(targets = "dev.silvergold.simulatedcoasters.track.cart.CoasterCartTrainLinkConstraint")
abstract class CoasterCartRemovedSyncMixin {
    @Unique
    private static final long RESEND_TICKS = 100;
    /** Game time of the last closing packet sent for each sub-level. Weak keys, so unloaded sub-levels drop out. */
    @Unique
    private static final Map<ServerSubLevel, Long> skyislands$lastSent = Collections.synchronizedMap(new WeakHashMap<>());

    @WrapOperation(method = "onCartRemoved", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Ldev/silvergold/simulatedcoasters/track/cart/CoasterCartTrainLinkSync;broadcastCart(Lnet/minecraft/server/level/ServerLevel;Ljava/util/UUID;Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)V"))
    private static void skyislands$sendRemovedAtMostEveryFewSeconds(ServerLevel level, UUID id, ServerSubLevel subLevel,
                                                                    Operation<Void> broadcastCart) {
        long now = level.getGameTime();
        Long last = skyislands$lastSent.get(subLevel);
        if (last != null && now - last < RESEND_TICKS) return;
        skyislands$lastSent.put(subLevel, now);
        broadcastCart.call(level, id, subLevel);
    }
}
