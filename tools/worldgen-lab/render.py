#!/usr/bin/env python3
"""Renders lab terrain to PNGs without a browser: a top-down map and an angled view.

usage: python3 render.py --cx 0 --cz 0 --area 2048 --step 4 --out /tmp/view [--settings file.json] [--yaw 45 --pitch 32]
Needs the lab running (http://127.0.0.1:8765), numpy and Pillow.
"""
import argparse, concurrent.futures as cf, gzip, json, math, struct, urllib.request
import numpy as np
from PIL import Image

B = "http://127.0.0.1:8765"
WATER = 1 << 24


def api(path, body=None):
    req = urllib.request.Request(B + path, data=None if body is None else json.dumps(body).encode(),
                                 method="GET" if body is None else "POST")
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.load(r)


def tile(tx, tz, step, v):
    with urllib.request.urlopen(f"{B}/api/tile?tx={tx}&tz={tz}&step={step}&v={v}", timeout=600) as r:
        data = r.read()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    o = 0
    def i32():
        nonlocal o
        val = struct.unpack_from(">i", data, o)[0]; o += 4; return val
    assert i32() == 0x57474C31
    _, tx_, tz_, step_, n, ox, oz = (i32() for _ in range(7))
    cols = []
    for _ in range(n * n):
        cnt = data[o]; o += 1
        runs = [struct.unpack_from(">hhi", data, o + 8 * k) for k in range(cnt)]
        o += 8 * cnt
        cols.append(runs)
    # inner 64x64 only
    return tx, tz, [[cols[(i + 1) * n + (j + 1)] for j in range(64)] for i in range(64)]


def load(cx, cz, area, step, v):
    span = 64 * step
    half = math.ceil(area / 2 / span)
    ctx, ctz = math.floor(cx / span), math.floor(cz / span)
    coords = [(ctx + dx, ctz + dz) for dz in range(-half, half) for dx in range(-half, half)]
    n = 2 * half * 64
    grid = [[None] * n for _ in range(n)]
    with cf.ThreadPoolExecutor(8) as ex:
        for tx, tz, cols in ex.map(lambda c: tile(c[0], c[1], step, v), coords):
            bi, bj = (tz - (ctz - half)) * 64, (tx - (ctx - half)) * 64
            for i in range(64):
                grid[bi + i][bj:bj + 64] = cols[i]
    x0, z0 = (ctx - half) * span, (ctz - half) * span
    return grid, x0, z0


def rgb(c):
    return np.array([(c >> 16) & 255, (c >> 8) & 255, c & 255], dtype=np.float32)


def topdown(grid, out):
    n = len(grid)
    img = np.zeros((n, n, 3), np.float32)
    height = np.full((n, n), np.nan, np.float32)
    for i in range(n):
        for j in range(n):
            runs = grid[i][j]
            if not runs:
                img[i, j] = (24, 30, 46)
                continue
            y0, y1, c = runs[-1]
            height[i, j] = y1
            img[i, j] = rgb(c & 0xFFFFFF)
    h = np.nan_to_num(height, nan=np.nanmin(height) - 20 if np.isfinite(height).any() else 0)
    gx = np.gradient(h, axis=1); gz = np.gradient(h, axis=0)
    shade = np.clip(1.0 - 0.06 * (gx + gz), 0.55, 1.25)
    lift = 0.75 + 0.5 * (h - np.nanmin(h)) / max(1.0, float(np.nanmax(h) - np.nanmin(h)))
    land = np.isfinite(height)[..., None]
    img = np.where(land, img * shade[..., None] * lift[..., None], img)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).resize((min(1400, n * 2),) * 2, Image.NEAREST).save(out)


def oblique(grid, step, out, yaw=45.0, pitch=32.0, width=1700):
    n = len(grid)
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
    # Project sample (i, j) and height y. Camera sits towards +x/+z looking back across the area.
    pts = []
    for i in range(n):
        for j in range(n):
            if grid[i][j]:
                X, Z = j * step, i * step
                u = X * cy - Z * sy
                d = X * sy + Z * cy  # larger = closer to the camera
                pts.append((d, u, grid[i][j]))
    if not pts:
        return
    us = np.array([p[1] for p in pts]); ds = np.array([p[0] for p in pts])
    ys = [r for p in pts for r in p[2]]
    ymin, ymax = min(r[0] for r in ys), max(r[1] for r in ys)
    umin, umax = us.min(), us.max()
    scale = width / (umax - umin + 40)
    def sv(y, d):
        return (-(y * cp) + d * sp) * scale
    vmin = sv(ymax + 10, ds.min()); vmax = sv(ymin - 10, ds.max())
    height = int(vmax - vmin) + 20
    img = np.zeros((height, width, 3), np.float32)
    sky_top, sky_bot = np.array([110, 160, 230], np.float32), np.array([200, 222, 245], np.float32)
    for r in range(height):
        img[r] = sky_top + (sky_bot - sky_top) * (r / height)
    fog = np.array([205, 222, 240], np.float32)
    dmin, dmax = ds.min(), ds.max()
    w = max(1, int(math.ceil(step * scale * 1.05)))
    cap = max(1, int(math.ceil(step * scale * sp)))
    order = np.argsort(ds)
    for k in order:
        d, u, runs = pts[k]
        px = int((u - umin + 20) * scale)
        f = 0.55 * (1.0 - (d - dmin) / max(1.0, dmax - dmin))  # farther = foggier
        for y0, y1, c in runs:
            col = rgb(c & 0xFFFFFF)
            water = bool(c & WATER)
            vt = int(sv(y1 + 1, d) - vmin) + 10
            vb = int(sv(y0, d) - vmin) + 10
            side = col * (0.62 if not water else 0.9)
            topc = col * (1.0 if not water else 1.05)
            if vb > vt:
                seg = img[max(0, vt):min(height, vb), max(0, px):min(width, px + w)]
                if seg.size:
                    grad = np.linspace(1.0, 0.75, seg.shape[0], dtype=np.float32)[:, None, None]
                    seg[:] = (side * grad) * (1 - f) + fog * f
            seg = img[max(0, vt - cap):min(height, vt), max(0, px):min(width, px + w)]
            if seg.size:
                seg[:] = topc * (1 - f) + fog * f
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(out)


def overview(cx, cz, size, out, n=768):
    """Top-down map of a big area from /api/map (shape maths only, no chunks). Water blue, height light to dark."""
    step = max(1, round(size / n))
    n = min(1024, size // step)
    x0, z0 = round(cx - size / 2), round(cz - size / 2)
    with urllib.request.urlopen(f"{B}/api/map?x0={x0}&z0={z0}&n={n}&step={step}", timeout=600) as r:
        data = r.read()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    n = struct.unpack_from(">i", data, 0)[0]
    rec = np.frombuffer(data, dtype=np.dtype([("top", ">f4"), ("bottom", ">f4"), ("water", ">f4"), ("count", "i1")]),
                        count=n * n, offset=4).reshape(n, n)
    top, water, count = rec["top"].astype(np.float32), rec["water"].astype(np.float32), rec["count"]
    land = np.isfinite(top)
    h = np.clip((np.nan_to_num(top, nan=90) - 90) / 220, 0, 1)
    t0 = np.nan_to_num(top, nan=0)
    shade = np.clip(1 + 0.05 * (np.gradient(t0, axis=1) + np.gradient(t0, axis=0)) * land, 0.6, 1.3)
    img = np.zeros((n, n, 3), np.float32)
    img[...] = (24, 30, 46)
    col = np.stack([70 + 140 * h, 120 + 90 * h, 50 + 110 * h], -1) * shade[..., None]
    img[land] = col[land]
    wet = land & np.isfinite(water) & (np.nan_to_num(water, nan=-1e9) >= t0)
    img[wet] = (60, 120, 200)
    img[land & (count > 1)] *= 0.85
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cx", type=int, default=0); ap.add_argument("--cz", type=int, default=0)
    ap.add_argument("--area", type=int, default=2048); ap.add_argument("--step", type=int, default=4)
    ap.add_argument("--out", default="/tmp/lab"); ap.add_argument("--settings")
    ap.add_argument("--yaw", type=float, default=45); ap.add_argument("--pitch", type=float, default=32)
    ap.add_argument("--map", type=int, help="also (or only, with --area 0) save a fast overview map this many blocks across")
    a = ap.parse_args()
    if a.settings:
        r = api("/api/settings", json.load(open(a.settings)))
        if "error" in r:
            raise SystemExit(r["error"])
        v = r["version"]
    else:
        v = api("/api/settings")["version"]
    if a.map:
        overview(a.cx, a.cz, a.map, a.out + "-map.png")
        print(f"v{v}: {a.out}-map.png, {a.map} blocks around {a.cx},{a.cz}")
        if a.area == 0:
            return
    grid, x0, z0 = load(a.cx, a.cz, a.area, a.step, v)
    topdown(grid, a.out + "-top.png")
    oblique(grid, a.step, a.out + "-view.png", a.yaw, a.pitch)
    print(f"v{v}: {a.out}-top.png and {a.out}-view.png, area {a.area} around {a.cx},{a.cz} (corner {x0},{z0}), step {a.step}")


if __name__ == "__main__":
    main()
