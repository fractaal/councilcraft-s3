package combatkick.mixin.physicsmod;

import combatkick.PhysicsModRagdolls;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Sees each ragdoll as blockifyEntity queues it, right after setting its velocity. */
@Pseudo
@Mixin(targets = "net.diebuddies.physics.PhysicsMod", remap = false)
public abstract class PhysicsModMixin {
    @ModifyArg(method = "blockifyEntity", remap = false,
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/ConcurrentLinkedQueue;add(Ljava/lang/Object;)Z", remap = false))
    private static Object combatkick$onRagdollQueued(Object ragdoll) {
        return PhysicsModRagdolls.onRagdollQueued(ragdoll);
    }
}
