#!/usr/bin/env python3
"""game: read and change a running Minecraft server through the Game Bridge mod.

  game status                              tick time, players online, loaded chunks and entities per dimension
  game players                             everyone online: where they are, health, Luck, what they hold
  game entities <selector>                 entities matching a player name or @e[...] selector
  game entities --near X Y Z [--radius R] [--dim D]
  game inv <target> [--ender]              a player's or mob's items (Curios slots included), one line per stack
  game inv --block X Y Z [--dim D]         a chest, barrel or any block with an item inventory
  game attr <target> [attribute id]        attributes with their modifiers (where a stat comes from)
  game nbt <target> [path]                 saved data, with a /data-style path; game nbt --block X Y Z [path]
  game find <block id> <target>            where a block is near a player or entity, as places with a block count;
  game find <block id> --near X Z          options: --radius R (default 96, max 256) --dim D --budget MS
  game map <target> --out FILE.png         a top-down picture of the area around a player or entity, north up;
  game map --near X Z --out FILE.png       options: --radius R (default 96, max 256) --scale 1-4 --dim D; --out - writes
                                           the PNG to standard output and the description to standard error
  game view <target> --out FILE.png        an isometric picture of the blocks around a player or entity: shows shape
  game view --near X Z --out FILE.png      and height, which the top-down map can't. Options: --radius R (default 32,
                                           max 64) --from south-east|south-west|north-west|north-east --scale 1|2
                                           --min-y Y --max-y Y (cut the world to a slab, to look under a roof) --dim D
  game cmd "<command>" [--wait MS]         run a console command and print everything it says; --wait keeps listening
                                           for answers that come late (locate needs --wait 10000 or so)
  game events [kind ...]                   live stream: chat join leave death advancement (all if none given)

Add --json to print the bridge's reply as JSON instead of the summary; --full on inv prints every item's components.

find, map and view read loaded chunks only (the bridge never loads a chunk to answer) and say how much of the area that was.
They don't see Sable airships, which live outside the normal chunks.

It talks to 127.0.0.1 on the machine it runs on, with the port and token from <server>/config/gamebridge.properties.
<server> is --server DIR, else $GAME_SERVER_DIR, else the server folder this script is installed under
(<server>/tools/gamebridge/game.py). Exit status is 0 on success, 1 when the bridge refuses or can't be reached (the
reason is printed, including whether anything was run), and 2 when cmd ran a command that the game rejected or that
reported failure.
"""
import argparse, base64, json, os, socket, sys
from pathlib import Path


def config(server_dir):
    path = Path(server_dir) / "config" / "gamebridge.properties"
    try:
        text = path.read_text()
    except OSError as e:
        sys.exit(f"game: can't read {path} ({e.strerror}). The bridge writes it on its first start; "
                 f"is the Game Bridge mod installed on this server, and can this user read the file?")
    props = dict(line.split("=", 1) for line in text.splitlines() if "=" in line and not line.startswith("#"))
    return int(props.get("port", "25585")), props.get("token", "")


def connect(port):
    try:
        return socket.create_connection(("127.0.0.1", port), timeout=5)
    except OSError as e:
        sys.exit(f"game: nothing is listening on 127.0.0.1:{port} ({e.strerror}). Either the server is down or still "
                 f"starting (the bridge opens when the log says Done), or the mod isn't installed. Nothing was run.")


def call(port, token, method, params, timeout=60):
    s = connect(port)
    s.settimeout(timeout)
    s.sendall((json.dumps({"id": 1, "token": token, "method": method, "params": params}) + "\n").encode())
    buf = b""
    try:
        while not buf.endswith(b"\n"):
            chunk = s.recv(65536)
            if not chunk:
                break
            buf += chunk
    except socket.timeout:
        sys.exit(f"game: no reply within {timeout} s. The request was sent, so it may have run or may still be running; "
                 f"check before repeating anything that changes the game.")
    finally:
        s.close()
    if not buf:
        sys.exit("game: the bridge closed the connection without replying. Whether the request ran is unknown.")
    reply = json.loads(buf)
    if not reply.get("ok"):
        err = reply.get("error", {})
        sys.exit(f"game: refused ({err.get('code')}): {err.get('message')}")
    return reply["result"]


def pos(p):
    return " ".join(f"{v:g}" for v in p)


def entity_line(e):
    bits = [e["type"].removeprefix("minecraft:"), repr(e["name"]), f"at {pos(e['pos'])}", e["dimension"].removeprefix("minecraft:")]
    if "health" in e:
        bits.append(f"hp {e['health']:g}/{e['max_health']:g}")
    for k in ("gamemode", "food", "xp_level", "luck"):
        if k in e:
            bits.append(f"{k} {e[k]}")
    if "holding" in e:
        bits.append("holding " + e["holding"])
    if "item" in e:
        bits.append(item_line(e["item"]))
    if "tags" in e:
        bits.append("tags " + ",".join(e["tags"]))
    return "  ".join(bits) + f"  [{e['uuid']}]"


SHORT = {"minecraft:enchantments": "ench", "minecraft:stored_enchantments": "stored ench", "apotheosis:affixes": "affixes",
         "apotheosis:rarity": "rarity", "apotheosis:sockets": "sockets", "apotheosis:purity": "purity", "apotheosis:gem": "gem",
         "minecraft:damage": "damage", "minecraft:custom_name": None, "minecraft:lore": None, "minecraft:attribute_modifiers": None}


def brief(v):
    if isinstance(v, dict):
        if set(v) == {"levels"} and isinstance(v["levels"], dict):
            v = v["levels"]
        return ", ".join(f"{str(k).split(':')[-1].split('/')[-1]} {brief(x)}" for k, x in v.items())
    if isinstance(v, float):
        return f"{v:.3g}"
    if isinstance(v, list):
        return f"{len(v)} entries"
    return str(v).removeprefix("apotheosis:").removeprefix("minecraft:")


def item_line(item):
    line = f"{item['count']}x {item['id']}"
    if item["name"] and item["name"].lower().replace(" ", "_").replace("'", "") not in item["id"]:
        line += f" \"{item['name']}\""
    comps = item.get("components", {})
    shown, other = [], []
    for k, v in comps.items():
        if k in SHORT:
            if SHORT[k]:
                shown.append(f"{SHORT[k]}: {brief(v)}")
        else:
            other.append(k.removeprefix("minecraft:"))
    if shown:
        line += "  [" + "; ".join(shown) + "]"
    if other:
        line += "  +" + ",".join(other)
    if "components_error" in item:
        line += "  (components unreadable: " + item["components_error"] + ")"
    return line


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--server", default=os.environ.get("GAME_SERVER_DIR") or str(Path(__file__).resolve().parents[2]))
    ap.add_argument("--json", action="store_true")
    sub = ap.add_subparsers(dest="what", required=True)
    sub.add_parser("status"); sub.add_parser("players")
    e = sub.add_parser("entities"); e.add_argument("selector", nargs="?"); e.add_argument("--near", nargs=3, type=float)
    e.add_argument("--radius", type=float, default=16); e.add_argument("--dim"); e.add_argument("--limit", type=int, default=100)
    i = sub.add_parser("inv"); i.add_argument("target", nargs="?"); i.add_argument("--block", nargs=3, type=int); i.add_argument("--dim")
    i.add_argument("--ender", action="store_true"); i.add_argument("--full", action="store_true")
    a = sub.add_parser("attr"); a.add_argument("target"); a.add_argument("attribute", nargs="?")
    n = sub.add_parser("nbt"); n.add_argument("target", nargs="?"); n.add_argument("path", nargs="?"); n.add_argument("--block", nargs=3, type=int)
    n.add_argument("--dim"); n.add_argument("--max-bytes", type=int)
    f = sub.add_parser("find"); f.add_argument("block"); f.add_argument("target", nargs="?"); f.add_argument("--near", nargs=2, type=float)
    f.add_argument("--radius", type=int); f.add_argument("--dim"); f.add_argument("--budget", type=int)
    m = sub.add_parser("map"); m.add_argument("target", nargs="?"); m.add_argument("--near", nargs=2, type=float)
    m.add_argument("--radius", type=int); m.add_argument("--scale", type=int); m.add_argument("--dim"); m.add_argument("--out", required=True)
    vw = sub.add_parser("view"); vw.add_argument("target", nargs="?"); vw.add_argument("--near", nargs=2, type=float)
    vw.add_argument("--radius", type=int); vw.add_argument("--scale", type=int); vw.add_argument("--dim"); vw.add_argument("--out", required=True)
    vw.add_argument("--from", dest="seen_from"); vw.add_argument("--min-y", type=int); vw.add_argument("--max-y", type=int); vw.add_argument("--budget", type=int)
    c = sub.add_parser("cmd"); c.add_argument("command"); c.add_argument("--wait", type=int, default=0)
    ev = sub.add_parser("events"); ev.add_argument("kinds", nargs="*")
    args = ap.parse_args()
    port, token = config(args.server)

    if args.what == "events":
        s = connect(port)
        params = {"events": args.kinds} if args.kinds else {}
        s.sendall((json.dumps({"id": 1, "token": token, "method": "subscribe", "params": params}) + "\n").encode())
        for raw in s.makefile("r", encoding="utf-8"):
            msg = json.loads(raw)
            if args.json:
                print(raw.strip(), flush=True); continue
            if "ok" in msg:
                if not msg["ok"]:
                    sys.exit(f"game: refused ({msg['error']['code']}): {msg['error']['message']}")
                print("listening for: " + ", ".join(msg["result"]["subscribed"]) + " (from now on; nothing earlier is replayed)", flush=True)
            elif msg["event"] == "ping":
                continue
            elif msg["event"] == "dropped":
                print(f"DROPPED {msg['count']} events: this reader fell behind and they are gone", flush=True)
            elif msg["event"] == "server_stopping":
                print("SERVER STOPPING: the stream ends here", flush=True); return
            else:
                kind = msg.pop("event"); msg.pop("time_ms", None); who = msg.pop("player", "?")
                where = f"{pos(msg.pop('pos'))} {msg.pop('dimension').removeprefix('minecraft:')}"
                rest = "  ".join(f"{k}={v}" for k, v in msg.items())
                print(f"{kind.upper()} {who} ({where})  {rest}", flush=True)
        sys.exit("game: the event stream closed. The server stopped or the connection dropped; start it again to keep listening.")

    params = {}
    if args.what == "entities":
        if args.near: params = {"near": args.near, "radius": args.radius}
        elif args.selector: params = {"selector": args.selector}
        else: sys.exit("game: entities needs a selector or --near X Y Z")
        params["limit"] = args.limit
    elif args.what in ("inv", "nbt"):
        if args.block: params["block"] = args.block
        elif args.target: params["target"] = args.target
        else: sys.exit(f"game: {args.what} needs a target or --block X Y Z")
        if args.what == "inv" and args.ender: params["ender_chest"] = True
        if args.what == "nbt":
            path = args.path if not args.block else (args.path or args.target)
            if path: params["path"] = path
            if args.max_bytes: params["max_bytes"] = args.max_bytes
    elif args.what == "attr":
        params = {"target": args.target}
        if args.attribute: params["attribute"] = args.attribute
    elif args.what == "cmd":
        params = {"command": args.command, "wait_ms": args.wait}
    elif args.what in ("find", "map", "view"):
        if args.near: params["near"] = args.near
        elif args.target: params["target"] = args.target
        else: sys.exit(f"game: {args.what} needs a player or entity to centre on, or --near X Z")
        if args.radius: params["radius"] = args.radius
        if args.what == "find":
            params["block"] = args.block
            if args.budget: params["budget_ms"] = args.budget
        elif args.scale: params["scale"] = args.scale
        if args.what == "view":
            for key, value in (("from", args.seen_from), ("min_y", args.min_y), ("max_y", args.max_y), ("budget_ms", args.budget)):
                if value is not None: params[key] = value
    if getattr(args, "dim", None): params["dimension"] = args.dim

    method = {"inv": "inventory", "attr": "attributes", "cmd": "command", "find": "find_blocks"}.get(args.what, args.what)
    r = call(port, token, method, params, timeout=60 + getattr(args, "wait", 0) / 1000)
    if args.what in ("map", "view"):
        png = base64.b64decode(r.pop("png_base64"))
        info = sys.stderr if args.out == "-" else sys.stdout
        if args.out == "-": sys.stdout.buffer.write(png)
        else: Path(args.out).write_bytes(png)
        if args.json:
            print(json.dumps(r, indent=1), file=info); return
    if args.what == "view":
        nw = r["north_west_corner_block"]
        print(f"{'PNG on standard output' if args.out == '-' else 'wrote ' + args.out}: {r['width']}x{r['height']} px isometric, seen from the {r['seen_from']}, "
              f"{r['dimension']}" + ("" if r["complete"] else ", INCOMPLETE"), file=info)
        print(f"  {r['reading']}", file=info)
        print(f"  area: {r['blocks_across']} blocks square from x {nw[0]}, z {nw[1]} (its north-west corner), y {r['y_from']} to {r['y_to']}; "
              f"one block of height is {r['pixels_per_block_up']} px; {r['blocks_drawn']} blocks drawn", file=info)
        if "note" in r: print("  " + r["note"], file=info)
        for pl in r["players_in_view"]:
            print(f"  player {pl['name']} at {pos(pl['pos'])}, pixel {pl['pixel'][0]},{pl['pixel'][1]} ({r['player_marker']})", file=info)
        return
    if args.what == "map":
        tl = r["top_left_block"]
        print(f"{'PNG on standard output' if args.out == '-' else 'wrote ' + args.out}: {r['width']}x{r['height']} px, {r['pixels_per_block']} px per block, "
              f"north up, {r['dimension']}", file=info)
        print(f"  top-left pixel is block x {tl[0]}, z {tl[1]}; pixel (px, py) is block x {tl[0]} + px/{r['pixels_per_block']}, z {tl[1]} + py/{r['pixels_per_block']}", file=info)
        print(f"  not loaded: {r['not_loaded_percent']}% of the area ({r['colour_not_loaded']})", file=info)
        print(f"  void: {r['void_percent']}% ({r['colour_void']})", file=info)
        if "lowest_surface_y" in r: print(f"  surface heights from y {r['lowest_surface_y']} to {r['highest_surface_y']}; {r['shading']}", file=info)
        for pl in r["players_in_view"]:
            print(f"  player {pl['name']} at {pos(pl['pos'])}, pixel {pl['pixel'][0]},{pl['pixel'][1]} ({r['player_marker']})", file=info)
        return
    if args.json:
        print(json.dumps(r, indent=1)); return

    if args.what == "find":
        c = r["centre"]
        print(f"{r['block']}: {r['blocks_found']} blocks in {r['places']} places within {r['radius']} of x {c[0]}, z {c[1]} ({r['dimension']}); "
              f"scanned {r['chunks_scanned']} of {r['chunks_in_range']} chunks" + ("" if r["complete"] else ", INCOMPLETE"))
        if "note" in r: print("  " + r["note"])
        for f in r["found"]:
            print(f"  {f['distance']:>4} blocks away: {pos(f['pos'])}  ({f['blocks']} blocks)")
    elif args.what == "status":
        print(f"{r['tps']:g} TPS, {r['mean_tick_ms']:g} ms per tick, {r['players_online']} online, Minecraft {r['minecraft']}, bridge {r['bridge_version']}")
        idle = [l for l in r["levels"] if not l["loaded_chunks"]]
        for l in r["levels"]:
            if l["loaded_chunks"]: print(f"  {l['dimension']}: {l['loaded_chunks']} chunks loaded, {l['entities']} entities")
        if idle: print(f"  {len(idle)} other dimensions have nothing loaded")
    elif args.what == "players":
        print(f"{len(r)} online")
        for e in r: print("  " + entity_line(e))
    elif args.what == "entities":
        print(f"{r['total']} match" + (f"  ({r['note']})" if "note" in r else ""))
        for e in r["entities"]: print("  " + entity_line(e))
    elif args.what == "inv":
        print(entity_line(r["of"]) if "of" in r else f"{r['block']}, {r['size']} slots")
        for k in ("selected_hotbar_slot", "note", "curios_error"):
            if k in r: print(f"  {k.replace('_', ' ')}: {r[k]}")
        for s in r["slots"]:
            print(f"  {s['where']:<18}{s['slot']:>3}  {item_line(s['item'])}")
            if args.full and s["item"].get("components"): print("      " + json.dumps(s["item"]["components"]))
        if not r["slots"]: print("  (empty)")
    elif args.what == "attr":
        print(entity_line(r["of"]))
        if "note" in r: print("  " + r["note"])
        for a in r["attributes"]:
            print(f"  {a['id']}: {a['value']:g} (base {a['base']:g})")
            for m in a["modifiers"]: print(f"      {m['amount']:+g} {m['operation']}  {m['id']}")
    elif args.what == "nbt":
        print(json.dumps(r["data"], indent=1) if r["matches"] == 1 else f"{r['matches']} matches:\n" + json.dumps(r["data"], indent=1))
    elif args.what == "cmd":
        for line in r["output"]: print(line)
        tail = f"success, result {r['result']}" if r["success"] else ("reported failure" if "result" in r else "no result reported")
        late = f"; {r['late_lines']} of them arrived late, in the {r['waited_ms']} ms it kept listening" if r["waited_ms"] else ""
        print(f"[{tail}; {len(r['output'])} lines{late}]")
        if "note" in r: print(f"[{r['note']}]")
        elif not r["output"] and not r["waited_ms"]:
            print("[It printed nothing. Commands that answer after they return, such as locate, need --wait MS to be heard.]")
        if not r["success"]: sys.exit(2)


if __name__ == "__main__":
    main()
