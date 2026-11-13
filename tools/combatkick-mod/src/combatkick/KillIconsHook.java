package combatkick;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** Public entry points for the mixins; the rest of the mod stays package-private. */
public final class KillIconsHook {
    public static void onDamageEvent(LivingEntity victim, DamageSource source) {
        KillIcons.onDamageEvent(victim, source);
    }

    public static void beforeDeath(LivingEntity victim) {
        RagdollFling.beforeDeath(victim);
    }

    public static void afterDeath() {
        RagdollFling.afterDeath();
    }

    private KillIconsHook() {}
}
