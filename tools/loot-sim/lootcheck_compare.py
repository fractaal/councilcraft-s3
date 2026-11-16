#!/usr/bin/env python3
"""Compares what the server really dropped (/lootcheck reports from the skyislands mod) with what sim.py expects.

  python3 -I lootcheck_compare.py --server DIR logs/lootcheck/*.json

DIR is the server folder the reports came from (its drops.json and quest files give the expected rates). For each report
it prints, per kind of drop, the count seen, the count expected for that many kills, and z: how many standard deviations
apart they are. |z| above 3 is marked, since chance alone gets there about once in 400 rows.

Expected bag counts come from sim.py's own Monte Carlo over the one mob in the report, at the Luck, dimension, difficulty
and spawn kind the report records, with the mob's health set to the average the report measured. Expected crate counts
are worked out exactly from the crate weights. The weapon's Looting level is read from the report. Things sim.py
doesn't model (weapon affixes such as Festive, the Fortune Talisman) show up here as differences, which is the point.
"""
import argparse, json, math, sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sim

BAG_ITEMS = {"mob_loot_bags:commonlootbag": "common", "mob_loot_bags:uncommonlootbag": "uncommon",
             "mob_loot_bags:rarelootbag": "rare", "mob_loot_bags:epiclootbag": "epic",
             "mob_loot_bags:legendarylootbag": "legendary", "mob_loot_bags:mythiclootbag": "mythic",
             "mob_loot_bags:cursed_lootbag": "cursed", "mob_loot_bags:timed_loot_bag": "_hourglass",
             "mob_loot_bags:locked_lootbags": "locked", "mob_loot_bags:summoning_lootbags": "summoning"}
SIM_KILLS = 3_000_000


def row(label, seen, expected, mc_count=None):
    """mc_count: the Monte Carlo count the expectation came from, so its own noise is allowed for; None if exact."""
    var = expected + (expected * expected / mc_count if mc_count else 0.0)
    z = (seen - expected) / math.sqrt(var) if var > 0 else (0.0 if seen == 0 else float("inf"))
    print(f"  {label:<34}{seen:>9}{expected:>11.1f}{z:>8.1f}{'  <-- check' if abs(z) > 3 else ''}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", required=True)
    ap.add_argument("--luck-bags", help="as in sim.py: on by default, 'off' leaves the LuckBags bonus out")
    ap.add_argument("reports", nargs="+")
    a = ap.parse_args()
    luck_bags = sim.parse_luck_bags(a.luck_bags)
    cfg = sim.load_jsonc(Path(a.server) / "config/mob_loot_bags/drops.json")
    crates_cfg = sim.load_crates(a.server)
    on, no_drop, crate_list = crates_cfg
    for path in a.reports:
        r = json.loads(Path(path).read_text())
        kills, mob, spawner = r["kills"], r["mob"], r["spawn"] == "spawner"
        dim = {"minecraft:the_nether": "nether", "minecraft:the_end": "end"}.get(r["dimension"], "overworld")
        p = sim.Profile("report", r["luck"], r.get("looting", 0), r.get("talisman", False), dim, r["difficulty"] == "hard", 1.0 if spawner else 0.0)
        print(f"\n{Path(path).name}: {kills} {mob} ({r['spawn']}), {r.get('player', '?')}, Luck {r['luck']:.2f}, "
              f"Looting {r.get('looting', 0)}{', talisman' if r.get('talisman') else ''}, holding {r.get('held', '?')}, {dim}, {r['difficulty']}"
              f"{', ' + str(r['survived']) + ' survived' if r['survived'] else ''}")
        print(f"  {'':<34}{'seen':>9}{'expected':>11}{'z':>8}")
        seen_bags, seen_crates, gems = Counter(), Counter(), 0
        for key, n in r["items"].items():
            item, _, detail = key.partition("[")
            if item in BAG_ITEMS:
                seen_bags[BAG_ITEMS[item]] += n
            elif item == "ftbquests:lootcrate":
                seen_crates[detail.split("]")[0]] += n
            elif item == "apotheosis:gem":
                gems += n
        if r.get("mean_max_health"):
            sim.MOBS[mob] = r["mean_max_health"]
        bags, _ = sim.simulate(cfg, crates_cfg, Counter({mob: 1}), p, SIM_KILLS, hourglass={}, luck_bags=luck_bags)
        scale = kills / SIM_KILLS
        for t in sim.TIERS + ["cursed", "_hourglass", "locked", "summoning"]:
            row("bag " + t.lstrip("_"), seen_bags[t], bags[t] * scale, bags[t])
        row("all bags", sum(seen_bags.values()), sum(bags[t] for t in BAG_ITEMS.values()) * scale,
            sum(bags[t] for t in BAG_ITEMS.values()))
        _health, passive, _known = sim.mob_info(mob)
        kind = "boss" if mob in sim.BAG_BOSSES else ("passive" if passive else "monster")
        total = no_drop[kind] + sum(w[kind] for _, w in crate_list)
        share = (sim.SPAWNER_CRATE_SHARE if spawner else 1.0) if on and total > 0 else 0.0
        for sid, w in crate_list:
            if w[kind] or seen_crates[sid]:
                row("crate " + sid, seen_crates[sid], kills * share * w[kind] / total)
        row("all crates", sum(seen_crates.values()), kills * share * sum(w[kind] for _, w in crate_list) / total if total else 0.0)
        unknown = set(seen_crates) - {sid for sid, _ in crate_list}
        if unknown:
            print(f"  crates the quest files don't name: {sorted(unknown)}")
        row("apotheosis gems", gems, bags["_gems"] * scale, bags["_gems"])


if __name__ == "__main__":
    main()
