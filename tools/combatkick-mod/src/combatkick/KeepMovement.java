package combatkick;

import com.leclowndu93150.heavier_weapons.client.HitstopManager;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

/**
 * Heavier Weapons zeroes WASD input for the whole hitstop, which also cancels sprinting. In LocalPlayer.aiStep
 * the order is: KeyboardInput.tick reads the keys, Heavier Weapons zeroes the impulses, then NeoForge fires
 * MovementInputUpdateEvent. So we remember what the keys produced and put it back in that event.
 */
public final class KeepMovement {
    private static final boolean HEAVIER_WEAPONS = ModList.get().isLoaded("heavier_weapons");
    private static float forward, left;

    /** Called from KeyboardInputMixin at the end of KeyboardInput.tick. */
    public static void remember(KeyboardInput input) {
        forward = input.forwardImpulse;
        left = input.leftImpulse;
    }

    static void onMovementInput(MovementInputUpdateEvent event) {
        if (!HEAVIER_WEAPONS || !KickConfig.KEEP_MOVEMENT.get() || !HitstopManager.isActive()) return;
        Input input = event.getInput();
        input.forwardImpulse = forward;
        input.leftImpulse = left;
    }

    private KeepMovement() {}
}
