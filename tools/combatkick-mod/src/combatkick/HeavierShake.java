package combatkick;

import net.minecraft.client.Minecraft;

/** Scales Heavier Weapons' camera shake by heavierWeaponsShake and the vanilla Screen Effect Scale slider. */
public final class HeavierShake {
    /** Called from mixin.heavier.CameraShakeMixin. */
    public static float scale(float intensity) {
        return intensity * KickConfig.HEAVIER_SHAKE.get().floatValue()
                * Minecraft.getInstance().options.screenEffectScale().get().floatValue();
    }

    private HeavierShake() {}
}
