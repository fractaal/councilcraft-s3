import sys, re
src = open('orig/java/lang/module/Resolver.java').read()
start = src.index("        // Iteratively update g1 until there are no more requires transitive")
end = src.index("        return g1;\n    }\n", start)
verify = len(sys.argv) > 1 and sys.argv[1] == 'verify'
new = r'''        // Propagate "requires transitive" into g1.
        //
        // The result is the same set the original loop converges to: each
        // module reads its direct reads plus everything reachable from them
        // along requires-transitive edges. The original loop re-checked every
        // (m1, m2, m3) triple with a HashSet lookup until nothing changed,
        // which is cubic in the module count. Here the reachability is
        // computed once with bit sets.
        long __t0 = System.nanoTime();
        HashMap<ResolvedModule, Integer> index = new HashMap<>();
        ArrayList<ResolvedModule> byIndex = new ArrayList<>();
        for (Map.Entry<ResolvedModule, Set<ResolvedModule>> e : g1.entrySet()) {
            if (index.putIfAbsent(e.getKey(), byIndex.size()) == null) byIndex.add(e.getKey());
            for (ResolvedModule m : e.getValue()) {
                if (index.putIfAbsent(m, byIndex.size()) == null) byIndex.add(m);
            }
        }
        for (Map.Entry<ResolvedModule, Set<ResolvedModule>> e : g2.entrySet()) {
            if (index.putIfAbsent(e.getKey(), byIndex.size()) == null) byIndex.add(e.getKey());
            for (ResolvedModule m : e.getValue()) {
                if (index.putIfAbsent(m, byIndex.size()) == null) byIndex.add(m);
            }
        }
        int n = byIndex.size();
        int words = (n + 63) >>> 6;

        // reach[i] = modules reachable from i along requires-transitive edges
        long[][] reach = new long[n][];
        for (Map.Entry<ResolvedModule, Set<ResolvedModule>> e : g2.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            long[] row = new long[words];
            for (ResolvedModule m : e.getValue()) {
                int j = index.get(m);
                row[j >>> 6] |= 1L << j;
            }
            reach[index.get(e.getKey())] = row;
        }
        // transitive closure (Warshall)
        for (int k = 0; k < n; k++) {
            long[] rk = reach[k];
            if (rk == null) continue;
            int kw = k >>> 6;
            long kb = 1L << k;
            for (int i = 0; i < n; i++) {
                long[] ri = reach[i];
                if (ri != null && i != k && (ri[kw] & kb) != 0) {
                    for (int w = 0; w < words; w++) ri[w] |= rk[w];
                }
            }
        }

        long[] have = new long[words];
        long[] want = new long[words];
        for (Set<ResolvedModule> m1Reads : g1.values()) {
            java.util.Arrays.fill(have, 0L);
            java.util.Arrays.fill(want, 0L);
            for (ResolvedModule m2 : m1Reads) {
                int j = index.get(m2);
                have[j >>> 6] |= 1L << j;
                long[] rj = reach[j];
                if (rj != null) {
                    for (int w = 0; w < words; w++) want[w] |= rj[w];
                }
            }
            for (int w = 0; w < words; w++) {
                long add = want[w] & ~have[w];
                while (add != 0) {
                    int bit = Long.numberOfTrailingZeros(add);
                    add &= add - 1;
                    m1Reads.add(byIndex.get((w << 6) + bit));
                }
            }
        }
'''
if verify:
    # run the original loop afterwards: it must find nothing to add, and the
    # result must equal what the original loop alone produces from a copy.
    new = r'''        Map<ResolvedModule, Set<ResolvedModule>> __copy = new HashMap<>();
        for (Map.Entry<ResolvedModule, Set<ResolvedModule>> e : g1.entrySet()) __copy.put(e.getKey(), new HashSet<>(e.getValue()));
''' + new + r'''        long __fast = System.nanoTime() - __t0;
        long __t1 = System.nanoTime();
        boolean changed;
        List<ResolvedModule> toAdd = new ArrayList<>();
        do {
            changed = false;
            for (Set<ResolvedModule> m1Reads : __copy.values()) {
                for (ResolvedModule m2 : m1Reads) {
                    Set<ResolvedModule> m2RequiresTransitive = g2.get(m2);
                    if (m2RequiresTransitive != null) {
                        for (ResolvedModule m3 : m2RequiresTransitive) {
                            if (!m1Reads.contains(m3)) toAdd.add(m3);
                        }
                    }
                }
                if (!toAdd.isEmpty()) { m1Reads.addAll(toAdd); toAdd.clear(); changed = true; }
            }
        } while (changed);
        long __edges = 0; for (Set<ResolvedModule> __s : g1.values()) __edges += __s.size();
        System.err.println("[RESOLVERFIX-VERIFY] modules=" + nameToReference.size() + " nodes=" + n + " edges=" + __edges
            + " fast=" + (__fast / 1000) + "us original=" + ((System.nanoTime() - __t1) / 1000000) + "ms identical=" + g1.equals(__copy));
'''
elif len(sys.argv) > 1 and sys.argv[1] == 'timed':
    new += r'''        System.err.println("[RESOLVERFIX] modules=" + nameToReference.size() + " nodes=" + n + " took " + ((System.nanoTime() - __t0) / 1000) + " us");
'''
out = src[:start] + new + "\n" + src[end:]
open('src/java/lang/module/Resolver.java', 'w').write(out)
