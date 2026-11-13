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

    /** Fall distance below which a landing does no damage (vanilla safe_fall_distance). */
    public static final float SAFE_FALL = 3.0F;

    /** Damaging landings up to this many ticks after a player's last hook update are logged. */
    public static final int LOG_TICKS = 200;

    /**
     * Logs a damaging landing shortly after grappling, so a fall death can be matched to what the cap did: whether the
     * landing was inside the cap's window, the fall distance before and after, and the speeds it was worked out from.
     */
    public static void logLanding(String player, int ticksSinceUpdate, float before, float after, double vy, double dy, double y) {
        com.mojang.logging.LogUtils.getLogger().info(
                "[skyislands] Grapple landing: {} at y {}, {} ticks after the last hook update, fall distance {} -> {} (vy {}, move dy {})",
                player, String.format("%.1f", y), ticksSinceUpdate, String.format("%.2f", before), String.format("%.2f", after),
                String.format("%.3f", vy), String.format("%.3f", dy));
    }

    /**
     * Forgiving Void (net.blay09.mods.forgivingvoid) marks a player it has sent back up from the void, and replaces the
     * damage of their next landing with its own (on this server, 100% of max health), however slowly they touch down.
     * It already lets an elytra flight end that state; this does the same for a grappling hook taking over, so a player
     * who hooks onto an island after a void fall takes ordinary, capped fall damage instead. The flag lives where Balm
     * keeps Forgiving Void's per-player data on NeoForge.
     */
    public static void endVoidFall(net.minecraft.server.level.ServerPlayer player) {
        // getPersistentData comes from NeoForge's patch to Entity, which this mod is compiled without.
        net.minecraft.nbt.CompoundTag balm = ((net.neoforged.neoforge.common.extensions.IEntityExtension) (Object) player)
                .getPersistentData().getCompound("PlayerPersisted").getCompound("BalmData");
        if (!balm.getBoolean("ForgivingVoidIsFalling")) return;
        balm.putBoolean("ForgivingVoidIsFalling", false);
        // Forgiving Void also switches off the movement checks for the fall; it turns them back on when the fall ends.
        player.hasChangedDimension();
        com.mojang.logging.LogUtils.getLogger().info("[skyislands] Grapple ended {}'s Forgiving Void fall at y {}",
                player.getScoreboardName(), String.format("%.1f", player.getY()));
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
