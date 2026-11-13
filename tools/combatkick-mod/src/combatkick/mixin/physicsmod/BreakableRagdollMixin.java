package combatkick.mixin.physicsmod;

import combatkick.PhysicsModRagdolls;
import net.diebuddies.physics.PhysicsWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every mob ragdoll type is a BreakableRagdoll; add() builds its bodies and gives them the starting velocity. */
@Pseudo
@Mixin(targets = "net.diebuddies.physics.ragdoll.BreakableRagdoll", remap = false)
public abstract class BreakableRagdollMixin {
    @Inject(method = "add", at = @At("TAIL"), remap = false)
    private void combatkick$onAdded(PhysicsWorld world, CallbackInfo ci) {
        PhysicsModRagdolls.onRagdollAdded(this);
    }
}
