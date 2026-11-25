"""Say which monument lakes a world has already generated chunks in.

usage: python3 -I monument_sites.py <world dir> <sites.json>

sites.json is the worldgen lab's /api/monuments answer for the world's seed. A monument is only built in chunks that
are generated after the skyislands jar with lake monuments is installed, so of the lakes that get one:
  - a lake whose monument chunk is already generated never gets its monument (the game decides a chunk's structures
    once, when the chunk is first made);
  - a lake whose monument chunk is new but with finished chunks under the monument gets a monument with those chunks
    missing from it.
Reads region files only, without loading chunks; run `save-all flush` first if the answer has to be exact.
"""
import json, os, re, struct, sys, zlib

world, sites = sys.argv[1], json.load(open(sys.argv[2]))
STATUS = re.compile(rb"\x08\x00\x06Status\x00.(minecraft:[a-z_]+)")
# A chunk at one of these has had its features placed, so a structure that turns up later is never built in it.
BUILT = {"minecraft:features", "minecraft:initialize_light", "minecraft:light", "minecraft:spawn", "minecraft:full"}
regions = {}


def status(cx, cz):
    """The chunk's saved status, or None if it was never saved."""
    key = (cx >> 5, cz >> 5)
    if key not in regions:
        path = os.path.join(world, "region", "r.%d.%d.mca" % key)
        regions[key] = open(path, "rb").read() if os.path.exists(path) else b""
    data = regions[key]
    if len(data) < 8192:
        return None
    i = (cx & 31) + (cz & 31) * 32
    sect = struct.unpack(">I", data[i * 4:i * 4 + 4])[0] >> 8
    if sect == 0:
        return None
    off = sect * 4096
    ln = struct.unpack(">I", data[off:off + 4])[0]
    if data[off + 4] != 2:
        return "?"
    m = STATUS.search(zlib.decompress(data[off + 5:off + 4 + ln]))
    return m.group(1).decode() if m else "?"


clear = lost = cut = 0
for s in sites["lakes"]:
    if not s["monument"]:
        continue
    cx, cz = s["chunkX"], s["chunkZ"]
    own = status(cx, cz)
    # The monument's box and the ring of water around it reach 36 blocks from the chunk's corner.
    under = [status(x, z) for x in range(cx - 3, cx + 3) for z in range(cz - 3, cz + 3)]
    built = sum(1 for u in under if u in BUILT)
    where = "monument at %d ~%d %d (lake island %d, %d, depth %.1f)" % (cx * 16, s["level"] - 13, cz * 16, s["x"], s["z"], s["depth"])
    if own is not None:
        lost += 1
        print("ALREADY GENERATED, no monument:", where, "- its chunk is", own)
    elif built:
        cut += 1
        print("WOULD BE CUT:", where, "- %d of the 36 chunks under it are already built" % built)
    else:
        clear += 1
print("%d monument lakes: %d untouched, %d already generated (no monument), %d would be cut by existing chunks" % (clear + lost + cut, clear, lost, cut))
