package combatkick.mixin;

import combatkick.KeepMovement;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void combatkick$remember(boolean isSneaking, float sneakingSpeedMultiplier, CallbackInfo ci) {
        KeepMovement.remember((KeyboardInput) (Object) this);
    }
}
