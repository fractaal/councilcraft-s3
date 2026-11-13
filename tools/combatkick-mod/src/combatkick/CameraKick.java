package combatkick;

import java.util.List;
import net.bettercombat.api.AttackHand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * A damped spring per camera axis. A hit adds an instant offset (the jolt); the spring pulls it back,
 * overshooting according to the damping ratio (the sway). Kicks stack, up to a cap.
 */
final class CameraKick {
    private static final Spring YAW = new Spring(), PITCH = new Spring(), ROLL = new Spring(), FOV = new Spring();
    private static long lastNanos;

    static void onAttackHit(LocalPlayer player, AttackHand hand, List<Entity> targets, Entity cursorTarget) {
        if (!KickConfig.ENABLED.get() || targets.stream().noneMatch(e -> e instanceof LivingEntity l && l.isAlive())) return;

        double kick = KickConfig.KICK_DEGREES.get() * KickConfig.STRENGTH.get() * weight(player)
                * Math.clamp(hand.attack().damageMultiplier(), 0.7, 1.6)
                * Math.min(1.0 + 0.15 * (targets.size() - 1), 1.6)
                * Minecraft.getInstance().options.screenEffectScale().get();
        if (isCrit(player)) kick *= KickConfig.CRIT_MULTIPLIER.get();

        Shape s = Shape.of(hand.attack().animation(), hand.isOffHand(), KickConfig.FOLLOW_SWING.get());
        double max = KickConfig.MAX_DEGREES.get();
        YAW.kick(kick * s.yaw, max);
        PITCH.kick(kick * s.pitch, max);
        ROLL.kick(kick * s.roll, max);
        // Negative = narrower FOV, a punch toward the target. Scaled relative to a 3-degree kick.
        FOV.kick(-KickConfig.FOV_PUNCH.get() * s.fov * kick / 3.0, 0.3);
    }

    static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.min((now - lastNanos) / 1e9, 0.1);
        lastNanos = now;
        double w = 2 * Math.PI * KickConfig.FREQUENCY.get(), z = KickConfig.DAMPING.get();
        YAW.step(dt, w, z);
        PITCH.step(dt, w, z);
        ROLL.step(dt, w, z);
        FOV.step(dt, w, Math.max(z, 0.8)); // the zoom settles without wobbling
        event.setYaw(event.getYaw() + (float) YAW.x);
        event.setPitch(event.getPitch() + (float) PITCH.x);
        event.setRoll(event.getRoll() + (float) ROLL.x);
    }

    static void onFov(ViewportEvent.ComputeFov event) {
        if (event.usedConfiguredFov() && FOV.x != 0) event.setFOV(event.getFOV() * (1 + FOV.x));
    }

    /** Slower weapons hit heavier: a 1.6-speed sword is 1.0, a 0.8-speed hammer 2.0, a 2.4-speed dagger about 0.67. */
    private static double weight(LocalPlayer player) {
        double speed = player.getAttributeValue(Attributes.ATTACK_SPEED);
        return speed <= 0 ? 1.0 : Math.clamp(1.6 / speed, 0.5, 2.5);
    }

    /** Vanilla's critical-hit test, minus the sprint check that Better Combat doesn't apply. */
    private static boolean isCrit(LocalPlayer p) {
        return p.fallDistance > 0 && !p.onGround() && !p.onClimbable() && !p.isInWater()
                && !p.hasEffect(MobEffects.BLINDNESS) && !p.isPassenger();
    }

    /** How a swing animation moves the camera, per unit of kick. */
    private record Shape(double yaw, double pitch, double roll, double fov) {
        static Shape of(String animation, boolean offHand, boolean follow) {
            String a = animation == null ? "" : animation.substring(animation.indexOf(':') + 1);
            // "_right" animations start on the right and sweep left; "_left" ones sweep right.
            double side = a.contains("_left") ? 1 : -1;
            if (offHand) side = -side;
            if (!follow) side = -side;
            double down = follow ? 1 : -1;
            Shape s;
            if (a.contains("spin") || a.contains("whirlwind")) s = new Shape(1.2 * side, 0, 0.3 * side, 0.5);
            else if (a.contains("stab") || a.contains("punch") || a.contains("throw")) s = new Shape(0, 0.35 * down, 0, 1.8);
            else if (a.contains("uppercut") || a.contains("upward")) s = new Shape(0.15 * side, -0.9 * down, 0.25 * side, 1.0);
            else if (a.contains("slam") || a.contains("vertical")) s = new Shape(0.15 * side, 1.0 * down, 0.25 * side, 1.3);
            else if (a.contains("uncross")) s = new Shape(0.5 * side, 0.2 * down, -0.8 * side, 1.0);
            else if (a.contains("cross")) s = new Shape(-0.5 * side, 0.2 * down, 0.8 * side, 1.0);
            else s = new Shape(1.0 * side, 0.2 * down, 0.45 * side, 1.0); // horizontal slashes and swipes
            return s;
        }
    }

    private static final class Spring {
        double x, v;

        void kick(double amount, double max) {
            x = Math.clamp(x + amount, -max, max);
        }

        /** Semi-implicit Euler in small substeps so low frame rates stay stable. */
        void step(double dt, double w, double z) {
            if (x == 0 && v == 0) return;
            int n = (int) Math.ceil(dt / 0.004);
            double h = dt / Math.max(n, 1);
            for (int i = 0; i < n; i++) {
                v += (-w * w * x - 2 * z * w * v) * h;
                x += v * h;
            }
            if (Math.abs(x) < 1e-4 && Math.abs(v) < 1e-3) x = v = 0;
        }
    }

    private CameraKick() {}
}
