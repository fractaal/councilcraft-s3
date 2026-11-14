package memtweaks;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;

/**
 * Keeps one BakedQuad object for each distinct quad. Models for different states of the same block mostly
 * repeat the same quads: of 5.77 million quads on this pack's client, 74% were exact copies of another
 * (heap dump, 2026-11-14). Quads are never changed after they are built, so models can share them.
 * Only plain BakedQuad objects are shared; a mod's own subclass may carry extra data and is left alone.
 */
public final class QuadShare {
    private QuadShare() {}

    private static final ObjectOpenCustomHashSet<BakedQuad> QUADS = new ObjectOpenCustomHashSet<>(new Hash.Strategy<>() {
        @Override public int hashCode(BakedQuad q) {
            int h = Arrays.hashCode(q.getVertices());
            h = 31 * h + q.getTintIndex();
            h = 31 * h + System.identityHashCode(q.getSprite());
            return 31 * h + (q.getDirection() == null ? 0 : q.getDirection().ordinal());
        }

        @Override public boolean equals(BakedQuad a, BakedQuad b) {
            if (a == b) return true;
            if (a == null || b == null) return false;
            return a.getTintIndex() == b.getTintIndex() && a.getDirection() == b.getDirection() && a.getSprite() == b.getSprite()
                    && a.isShade() == b.isShade() && a.hasAmbientOcclusion() == b.hasAmbientOcclusion()
                    && (a.getVertices() == b.getVertices() || Arrays.equals(a.getVertices(), b.getVertices()));
        }
    });
    private static long seen, dropped;

    /** Replaces each quad in a new model's lists with the kept copy of it. Lists that can't be changed are left as they are. */
    public static void share(List<BakedQuad> unculled, Map<Direction, List<BakedQuad>> culled) {
        synchronized (QUADS) {
            shareList(unculled);
            if (culled != null) for (List<BakedQuad> list : culled.values()) shareList(list);
        }
    }

    private static void shareList(List<BakedQuad> list) {
        if (list == null || list.isEmpty()) return;
        try {
            list.replaceAll(QuadShare::keptCopy);
        } catch (UnsupportedOperationException ignored) {
            // An unmodifiable list: leave this model's quads alone.
        }
    }

    private static BakedQuad keptCopy(BakedQuad quad) {
        if (quad == null || quad.getClass() != BakedQuad.class) return quad;
        seen++;
        BakedQuad kept = QUADS.addOrGet(quad);
        if (kept != quad) dropped++;
        return kept;
    }

    /** A resource reload makes new sprites, so nothing built before it can match again. */
    public static void clear() {
        synchronized (QUADS) {
            QUADS.clear();
            QUADS.trim();
        }
    }

    public static String stats() {
        synchronized (QUADS) {
            return seen + " quads seen, " + dropped + " duplicates dropped, " + QUADS.size() + " kept";
        }
    }
}
