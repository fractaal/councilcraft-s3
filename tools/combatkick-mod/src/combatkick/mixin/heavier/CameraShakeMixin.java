package combatkick.mixin.heavier;

import combatkick.HeavierShake;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Heavier Weapons starts its camera shake here, with its own intensity already applied; we scale it once more. */
@Pseudo
@Mixin(targets = "com.leclowndu93150.heavier_weapons.client.CameraShakeManager", remap = false)
public abstract class CameraShakeMixin {
    @ModifyVariable(method = "startShake", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private static float combatkick$scaleShake(float intensity) {
        return HeavierShake.scale(intensity);
    }
}
