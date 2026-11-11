"""List every generated structure in a world and say whether it sits on solid ground.

usage: python3 -I structscan.py <analyze.py> <world dir> [out.json]

For each structure start we union the child bounding boxes, then sample a 5x5 grid of columns across the
footprint and look at the block directly under the box (floor) and directly over it (roof):
  floor_solid  fraction of footprint samples with a non-air, non-fluid block just under the box
  roof_solid   fraction with a solid block just above the box (the structure is buried)
  inside_solid fraction of samples where the block at the box's own middle height is solid
Floating in the void shows up as floor_solid close to 0.
"""
import sys, struct, zlib, glob, os, json, collections, math

exec(open(sys.argv[1]).read().split("world=sys.argv[1]")[0])  # nbt(), hm()
world = sys.argv[2]
out = sys.argv[3] if len(sys.argv) > 3 else None

DONE = {"minecraft:features", "minecraft:initialize_light", "minecraft:light", "minecraft:spawn", "minecraft:full"}
AIRLIKE = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:water", "minecraft:lava"}
chunks = {}  # (cx, cz) -> parsed chunk with section lookup


def load_regions():
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
            if c.get("Status") not in DONE:
                continue
            secs = {}
            for s in c.get("sections", []):
                bs = s.get("block_states")
                if bs:
                    secs[s["Y"]] = bs
            chunks[(rx * 32 + (i % 32), rz * 32 + (i // 32))] = (c, secs)


def block(x, y, z):
    ck = chunks.get((x >> 4, z >> 4))
    if ck is None:
        return None
    bs = ck[1].get(y >> 4)
    if bs is None:
        return "minecraft:air"
    pal = bs["palette"]
    if len(pal) == 1 or "data" not in bs:
        return pal[0]["Name"]
    bits = max(4, math.ceil(math.log2(len(pal))))
    per = 64 // bits
    idx = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15)
    lng = bs["data"][idx // per] & ((1 << 64) - 1)
    return pal[(lng >> (bits * (idx % per))) & ((1 << bits) - 1)]["Name"]


def solid(x, y, z):
    b = block(x, y, z)
    return None if b is None else b not in AIRLIKE


load_regions()
print("full chunks loaded:", len(chunks))
res = []
for (cx, cz), (c, _) in chunks.items():
    for sid, st_ in c.get("structures", {}).get("starts", {}).items():
        if not isinstance(st_, dict) or st_.get("id") == "INVALID":
            continue
        if st_.get("ChunkX") != cx or st_.get("ChunkZ") != cz:
            continue
        bbs = [ch["BB"] for ch in st_.get("Children", []) if "BB" in ch]
        if not bbs:
            continue
        x0 = min(b[0] for b in bbs); y0 = min(b[1] for b in bbs); z0 = min(b[2] for b in bbs)
        x1 = max(b[3] for b in bbs); y1 = max(b[4] for b in bbs); z1 = max(b[5] for b in bbs)
        floor = roof = inside = n = unknown = 0
        ymid = (y0 + y1) // 2
        for i in range(5):
            for j in range(5):
                x = x0 + (x1 - x0) * i // 4; z = z0 + (z1 - z0) * j // 4
                f, r, m = solid(x, y0 - 1, z), solid(x, y1 + 1, z), solid(x, ymid, z)
                if f is None or r is None or m is None:
                    unknown += 1; continue
                n += 1; floor += f; roof += r; inside += m
        if n == 0:
            continue
        res.append(dict(id=sid, chunk=[cx, cz], x=(x0 + x1) // 2, z=(z0 + z1) // 2, y0=y0, y1=y1,
                        size=[x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1],
                        floor_solid=round(floor / n, 2), roof_solid=round(roof / n, 2), inside_solid=round(inside / n, 2), samples=n))

if out:
    json.dump(res, open(out, "w"), indent=0)
by = collections.defaultdict(list)
for r in res:
    by[r["id"]].append(r)
print("structures:", len(res), "kinds:", len(by))
print(f'{"structure":46} {"n":>3} {"y range":>13} {"floor solid":>11} {"roof solid":>10} {"floating(<25% floor)":>20}')
for sid, rs in sorted(by.items(), key=lambda kv: -len(kv[1])):
    ys0 = min(r["y0"] for r in rs); ys1 = max(r["y1"] for r in rs)
    fl = sum(r["floor_solid"] for r in rs) / len(rs); rf = sum(r["roof_solid"] for r in rs) / len(rs)
    fl_n = sum(1 for r in rs if r["floor_solid"] < 0.25)
    print(f"{sid:46} {len(rs):>3} {ys0:>5}..{ys1:<6} {fl*100:>10.0f}% {rf*100:>9.0f}% {fl_n:>10}/{len(rs)}")
