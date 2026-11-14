package memtweaks;

import java.util.Set;

/**
 * A set of every block state's and item's model location (2.5 million entries in this pack) is built several
 * times in each resource load, each time from registries that are frozen and so always give the same result:
 *
 *  - by ModernFix at the start of NeoForge's ModifyBakingResult event,
 *  - by Puzzles Lib inside that event, once for each mod that listens to its model events,
 *  - by ModernFix again for the BakingCompleted event a few seconds later.
 *
 * This keeps the first one. Puzzles Lib is handed it during the event (its callers only iterate it), and
 * ModernFix's second build is filled by copying it. It is let go when BakingCompleted has been delivered.
 */
public final class ModelLocationShare {
    private static volatile Set<?> built;       // the most recent complete set
    private static volatile boolean inEvent;
    private static Set<?> filling;              // a set currently being filled from `built`
    private static int reused;

    private ModelLocationShare() {}

    /** ModernFix finished building a set. */
    public static void keep(Set<?> set) {
        if (MemTweaks.reuseModelLocationList()) built = set;
        filling = null;
    }

    public static void eventStarted() {
        inEvent = true;
        reused = 0;
    }

    /** For Puzzles Lib: the set built for this event, or null outside the event or when sharing is off. */
    public static Set<?> get() {
        Set<?> s = inEvent ? built : null;
        if (s != null) reused++;
        return s;
    }

    /** Puzzles Lib built its own set (ModernFix's wasn't available): keep that one for its later callers. */
    public static void keepOwn(Set<?> set) {
        if (inEvent && built == null && MemTweaks.reuseModelLocationList()) built = set;
    }

    /** The ModifyBakingResult event has been delivered to every mod. */
    public static void eventFinished() {
        inEvent = false;
        Set<?> s = built;
        if (s != null) MemTweaks.logModelLocationShare(s.size(), reused);
    }

    /**
     * Called for each block as ModernFix adds its model locations to a set it is building. If an earlier
     * complete set exists, the new one is filled from it once and every per-block call is skipped.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean fillFromPrevious(Set destination) {
        if (destination == filling) return true;
        Set<?> previous = built;
        if (previous == null || destination == previous || !destination.isEmpty()) return false;
        destination.addAll(previous);
        filling = destination;
        return true;
    }

    /** The BakingCompleted event has been delivered; nothing needs the set until the next resource load. */
    public static void bakingCompleted() {
        built = null;
        filling = null;
    }
}
