package skyislands;

/**
 * Grappling-hook landings (compat with the Grappling Hook mod, com.yyon.grapplinghook).
 *
 * While the hook mod steers a player (on the rope, or in free flight with overrideMovementInAir on, which keeps the
 * player's momentum after letting go), the client sends the server a movement update every tick, and the server sets
 * the player's fall distance from the vertical speed the client's own simulation reports. That simulated speed can be
 * large at the moment the player touches down, for example when the simulation keeps applying gravity for a tick or
 * two after the player is already standing, so a gentle landing could kill.
 *
 * Here the server keeps the player's height from those updates and works out the real vertical speed from them. When
 * the player lands within a few ticks of the last update, their fall distance is capped at what that real speed would
 * build up in a vanilla fall. A landing at speed still hurts as much as falling that fast would; a gentle one doesn't.
 * Falls that start after the hook mod has stopped steering are untouched.
 */
public final class GrappleLanding {
    /** Updates no older than this (ticks) count as "being steered by the hook mod" at landing. */
    public static final int RECENT_TICKS = 5;

    private GrappleLanding() {}

    private static volatile boolean logged;

    /** Logged once, so the server log shows the hook mod's updates reach this code. */
    public static void logFirstUpdate() {
        if (logged) return;
        logged = true;
        com.mojang.logging.LogUtils.getLogger().info("[skyislands] Grapple landing cap: receiving the hook mod's movement updates");
    }

    /** Added to ServerPlayer by mixin.ServerPlayerGrappleMixin. */
    public interface Tracked {
        void skyislands$grappleUpdate(int tick, double y);

        /** Vertical speed (blocks per tick, negative falling) from the last two updates, or NaN if not recent. */
        double skyislands$grappleVerticalSpeed(int tick);
    }

    /** The fall distance a vanilla fall builds up to reach this downward speed (gravity 0.08 blocks per tick per tick). */
    public static float fallDistanceFor(double verticalSpeed) {
        double down = Math.max(0.0, -verticalSpeed);
        return (float) (down * down / 0.16);
    }
}
