package combatkick;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.diebuddies.physics.IRigidBody;
import net.diebuddies.physics.ragdoll.Ragdoll;
import physx.common.PxVec3;
import physx.physics.PxRigidDynamic;

/**
 * The Physics Mod side of {@link RagdollFling}, called only from the optional Physics Mod mixins, so nothing here
 * loads without Physics Mod. A ragdoll is created on the render thread inside {@code die}, but its bodies are
 * built later in {@code Ragdoll.add} by the physics world, hence the hand-over map.
 */
public final class PhysicsModRagdolls {
    private record Tagged(RagdollFling.Hit hit, long ms) {}
    private static final Map<Object, Tagged> tagged = new ConcurrentHashMap<>();

    /** A ragdoll Physics Mod is queueing; if it's for the death we just flung, remember where that hit landed. */
    public static Object onRagdollQueued(Object ragdoll) {
        RagdollFling.Hit hit = RagdollFling.armed;
        if (hit != null) {
            RagdollFling.armed = null;
            long now = System.currentTimeMillis();
            tagged.values().removeIf(t -> now - t.ms > 5000); // ragdolls over Physics Mod's limit never get added
            tagged.put(ragdoll, new Tagged(hit, now));
        }
        return ragdoll;
    }

    /**
     * After Physics Mod has built a ragdoll's bodies and given them all the same velocity, add the hit to the one
     * part nearest where it landed. Parts are matched by height up the body and by sideways offset from its middle,
     * which holds whatever origin the physics world uses.
     */
    public static void onRagdollAdded(Object ragdoll) {
        Tagged t = tagged.remove(ragdoll);
        if (t == null) return;
        RagdollFling.Hit hit = t.hit;
        List<PxRigidDynamic> parts = new ArrayList<>();
        List<float[]> pos = new ArrayList<>();
        for (IRigidBody b : ((Ragdoll) ragdoll).btBodies) {
            if (!(b.getRigidBody() instanceof PxRigidDynamic d)) continue;
            PxVec3 p = d.getGlobalPose().getP();
            parts.add(d);
            pos.add(new float[] {p.getX(), p.getY(), p.getZ()});
        }
        if (parts.isEmpty()) return;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE, cx = 0, cz = 0;
        for (float[] p : pos) {
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
            cx += p[0] / pos.size();
            cz += p[2] / pos.size();
        }
        // Part centres run from about 10% to 90% of the mob's height; stretch the hit height onto that span.
        double h = Math.max(hit.height(), 0.3);
        double target = minY + Math.clamp((hit.hitOffset().y / h - 0.1) / 0.8, 0, 1) * (maxY - minY);
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < parts.size(); i++) {
            float[] p = pos.get(i);
            double dx = p[0] - cx - hit.hitOffset().x, dy = p[1] - target, dz = p[2] - cz - hit.hitOffset().z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < best) {
                best = d2;
                nearest = i;
            }
        }
        PxRigidDynamic d = parts.get(nearest);
        PxVec3 v = d.getLinearVelocity();
        v.setX(v.getX() + (float) hit.partVelocity().x);
        v.setY(v.getY() + (float) hit.partVelocity().y);
        v.setZ(v.getZ() + (float) hit.partVelocity().z);
        d.setLinearVelocity(v, true);
    }

    private PhysicsModRagdolls() {}
}
