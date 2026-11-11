"""Compare terrain column by column between two worlds that share a seed.

usage: HMBITS=10 python3 -I worldcmp.py <analyze.py> <worldA> <worldB>

Only chunks that are fully generated in BOTH worlds are compared. For each column we compare
MOTION_BLOCKING (top of anything solid or water) and OCEAN_FLOOR (top of solid ground).
A column where MOTION_BLOCKING > OCEAN_FLOOR holds water.
"""
import sys, struct, zlib, glob, os, collections

exec(open(sys.argv[1]).read().split("world=sys.argv[1]")[0])  # nbt(), hm()


def load(world):
    out = {}
    for path in glob.glob(world + "/region/r.*.mca"):
        rx, rz = map(int, os.path.basename(path).split(".")[1:3])
        data = open(path, "rb").read()
        if len(data) < 8192:
            continue
        for i in range(1024):
            sect = struct.unpack(">I", data[i * 4:i * 4 + 4])[0] >> 8
            if sect == 0:
                continue
            ln = struct.unpack(">I", data[sect * 4096:sect * 4096 + 4])[0]
            if data[sect * 4096 + 4] != 2:
                continue
            c = nbt(zlib.decompress(data[sect * 4096 + 5:sect * 4096 + 4 + ln]))
            H = c.get("Heightmaps", {})
            if c.get("Status") != "minecraft:full" or not all(k in H for k in ("MOTION_BLOCKING", "OCEAN_FLOOR", "MOTION_BLOCKING_NO_LEAVES")):
                continue
            miny = c.get("yPos", -4) * 16
            out[(rx * 32 + (i % 32), rz * 32 + (i // 32))] = (hm(H["MOTION_BLOCKING"], miny), hm(H["OCEAN_FLOOR"], miny), hm(H["MOTION_BLOCKING_NO_LEAVES"], miny))
    return out


A, B = load(sys.argv[2]), load(sys.argv[3])
common = sorted(set(A) & set(B))
print(f"full chunks: A={len(A)} B={len(B)} shared={len(common)}")
cols = same_floor = same_top = 0
water_a = water_b = water_both = water_same_surface = 0
diffs = []
bad_chunks = collections.Counter()
for ck in common:
    (mbA, ofA, nlA), (mbB, ofB, nlB) = A[ck], B[ck]
    for i in range(256):
        cols += 1
        same_floor += nlA[i] == nlB[i]
        same_top += mbA[i] == mbB[i]
        if nlA[i] != nlB[i]:
            diffs.append(abs(nlA[i] - nlB[i])); bad_chunks[ck] += 1
        wa, wb = mbA[i] > ofA[i], mbB[i] > ofB[i]
        water_a += wa; water_b += wb
        if wa and wb:
            water_both += 1
            water_same_surface += mbA[i] == mbB[i]
print(f"columns compared: {cols}")
print(f"top ignoring leaves identical: {same_floor} ({100*same_floor/max(1,cols):.2f}%)   top incl. leaves identical: {same_top} ({100*same_top/max(1,cols):.2f}%)")
if diffs:
    diffs.sort()
    print(f"top ignoring leaves differs in {len(diffs)} columns; median diff {diffs[len(diffs)//2]}, p95 {diffs[int(len(diffs)*.95)]}, max {diffs[-1]}; chunks affected: {len(bad_chunks)} of {len(common)}")
print(f"water columns: A={water_a} B={water_b} both={water_both}; same surface level in {water_same_surface} of the shared ones")
