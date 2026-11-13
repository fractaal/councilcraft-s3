package combatkick.mixin;

import combatkick.KillIconsHook;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The client's view of a damage event packet: who hurt whom. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "handleDamageEvent", at = @At("HEAD"))
    private void combatkick$onDamageEvent(DamageSource source, CallbackInfo ci) {
        KillIconsHook.onDamageEvent((LivingEntity) (Object) this, source);
    }

    /** Entity event 3 is death; on the client it calls die(), where Physics Mod spawns the ragdoll. */
    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void combatkick$beforeDeath(byte id, CallbackInfo ci) {
        if (id == 3) KillIconsHook.beforeDeath((LivingEntity) (Object) this);
    }

    @Inject(method = "handleEntityEvent", at = @At("RETURN"))
    private void combatkick$afterDeath(byte id, CallbackInfo ci) {
        if (id == 3) KillIconsHook.afterDeath();
    }
}
