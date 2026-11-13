package combatkick;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bettercombat.api.AttackHand;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * Throws Physics Mod ragdolls by the body part the killing swing struck, the way the swing was going; the rest of
 * the body only follows through its joints.
 *
 * <p>Physics Mod 3.0.35 starts a ragdoll, in {@code PhysicsMod.blockifyEntity} at the head of {@code LivingEntity.die},
 * with velocity {@code normalize(mob - nearestPlayer, y = 2) * 5 + deltaMovement * 10} (blocks per second), given
 * to every body part alike. The fixed push points steeply upward at sword range, which makes bodies float, and
 * Heavier Weapons holds the server's knockback back during hitstop, so the mob's own velocity adds almost nothing.
 *
 * <p>Just before death we set the mob's client-side velocity so that formula comes out at zero, and arm the fling as
 * a hit on one spot. {@link PhysicsModRagdolls} tags the ragdoll Physics Mod then creates and, once its bodies
 * exist, drives the one part nearest that spot. Remote mobs don't move by their
 * client-side velocity, so none of this changes anything when Physics Mod is absent or ragdolls are off.
 */
final class RagdollFling {
    private static final boolean PHYSICS_MOD = ModList.get().isLoaded("physicsmod");
    /** Physics Mod's own push, and the factor it applies to the mob's velocity. */
    private static final double PM_PUSH = 5.0, PM_VELOCITY_SCALE = 10.0;
    /** A death this many ticks after the swing still counts; covers ping plus hitstop. */
    private static final long WINDOW_TICKS = 40;
    /** The struck part is light next to the whole body, so it gets this multiple of the fling speed. */
    private static final double PART_GAIN = 2.5;

    /** @param hitOffset where the swing landed, relative to the mob's feet; @param height the mob's height */
    record Hit(Vec3 partVelocity, Vec3 hitOffset, double height) {}
    private record Fling(Vec3 velocity, Vec3 hitOffset, long tick) {}
    private static final Map<Integer, Fling> pending = new HashMap<>();
    /** Set during a death we flung, for the ragdoll Physics Mod creates inside that same call. */
    static Hit armed;

    static void onAttackHit(LocalPlayer player, AttackHand hand, List<Entity> targets, Entity cursorTarget) {
        if (!PHYSICS_MOD || !KickConfig.RAGDOLL_FLING.get()) return;
        long now = player.level().getGameTime();
        pending.values().removeIf(f -> now - f.tick > WINDOW_TICKS);

        CameraKick.Shape s = CameraKick.Shape.of(hand.attack().animation(), hand.isOffHand(), true);
        double yaw = Math.toRadians(player.getYRot());
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)), right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        // With followSwing on, Shape's yaw is the sweep (+ = to the right), pitch is downward and fov is forwardness.
        // The constant lift keeps level slashes from driving bodies into the floor.
        Vec3 dir = forward.scale(0.5 * s.fov()).add(right.scale(s.yaw())).add(0, 0.35 - s.pitch(), 0).normalize();
        double speed = KickConfig.RAGDOLL_FLING_SPEED.get() * CameraKick.weight(player)
                * (CameraKick.isCrit(player) ? 1.4 : 1.0);
        Vec3 velocity = dir.scale(speed);
        Vec3 eye = player.getEyePosition(), look = player.getLookAngle();
        for (Entity e : targets) {
            if (e instanceof LivingEntity) pending.put(e.getId(), new Fling(velocity, aimPoint(eye, look, e.getBoundingBox()).subtract(e.position()), now));
        }
    }

    /** The point of the box nearest the aim line: the aim line's closest approach to the box centre, clamped into it. */
    private static Vec3 aimPoint(Vec3 eye, Vec3 look, AABB box) {
        Vec3 p = eye.add(look.scale(box.getCenter().subtract(eye).dot(look)));
        return new Vec3(Math.clamp(p.x, box.minX, box.maxX), Math.clamp(p.y, box.minY, box.maxY), Math.clamp(p.z, box.minZ, box.maxZ));
    }

    /** Called from the client's entity event 3 (death), just before it runs {@code die}. */
    static void beforeDeath(LivingEntity mob) {
        armed = null;
        Fling f = pending.remove(mob.getId());
        if (f == null || mob.level().getGameTime() - f.tick > WINDOW_TICKS) return;
        Vec3 push = Vec3.ZERO;
        Player near = mob.level().getNearestPlayer(mob.getX(), mob.getY(), mob.getZ(), 8.0, false);
        if (near != null) push = new Vec3(mob.getX() - near.getX(), 2.0, mob.getZ() - near.getZ()).normalize().scale(PM_PUSH);
        mob.setDeltaMovement(push.scale(-1 / PM_VELOCITY_SCALE));
        armed = new Hit(f.velocity.scale(PART_GAIN), f.hitOffset, mob.getBbHeight());
    }

    /** Called once the death event has run, so a hit that didn't become a ragdoll can't tag the next one. */
    static void afterDeath() {
        armed = null;
    }

    private RagdollFling() {}
}
