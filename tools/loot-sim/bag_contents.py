#!/usr/bin/env python3
"""What Mob Loot Bags actually hold: Monte Carlo over the live bag loot tables, the chest tables they nest, and
Apotheosis's gem and affix entries, at a given Luck and Apotheosis world tier.

  python3 -I bag_contents.py --data DIR                     every tier at luck 0 / haven and luck 7 / ascent
  python3 -I bag_contents.py --data DIR --luck 29.6 --tier summit
  python3 -I bag_contents.py --data DIR --json              per-bag means, for sim.py --contents

DIR holds the server's data sources in load order: the vanilla server-*-extra.jar under libraries/, mods/*.jar, global_packs/required_data/*, world/datapacks/*
(each a jar/zip or a folder; later sources override earlier ones, as datapacks override mod jars).

How a bag opens (decompiled from mob_loot_bags 2.0.1 LootService.roll): table.getRandomItems(CHEST params with
THIS_ENTITY = the player and luck = player.getLuck()). That is the player's whole Luck attribute; the drops.json
luck_attribute multiplier only applies to the tier roll. NeoForge runs global loot modifiers once, on the bag's own
table id (mob_loot_bags:bags/<tier>[/<dim>]), never on the chest tables it nests. So of Apotheosis 8.9.0's modifiers
only affix_conversion matches (path ".*", 35% per affixable item); gem_loot_injection and affix_loot_injection want
"chests.*" and never fire on bags. Gems and affix items otherwise come only from nested tables that contain
apotheosis:random_gem / apotheosis:random_affix_item entries (chiefly apotheosis:chests/*).

Approximations: conditions that need a killer, tool or block (killed_by_player, match_tool, entity_properties, ...)
fail, as they would with these params; location and weather checks pass; which items Apotheosis can affix is guessed
from item ids (see GEAR); enchanting functions are ignored.
"""
import argparse, json, math, os, random, re, sys, zipfile
from collections import Counter, defaultdict
from pathlib import Path

TIERS = ["common", "uncommon", "rare", "epic", "legendary", "mythic"]
WORLD_TIERS = ["haven", "frontier", "ascent", "summit", "pinnacle"]
PURITIES = ["cracked", "chipped", "flawed", "normal", "flawless", "perfect"]
RARITIES = ["common", "uncommon", "rare", "epic", "mythic"]
NOTABLE = ["minecraft:nether_star", "minecraft:netherite_ingot", "minecraft:enchanted_golden_apple",
           "minecraft:totem_of_undying", "minecraft:heavy_core", "minecraft:beacon",
           "minecraft:netherite_upgrade_smithing_template", "minecraft:diamond", "apotheosis:gem_dust"]
GEAR = re.compile(r"(_sword|_axe|_pickaxe|_shovel|_helmet|_chestplate|_leggings|_boots|^minecraft:bow|crossbow|"
                  r"trident|shield|mace|_claymore|_katana|_greataxe|_greathammer|_spear|_glaive|_rapier|_scythe|"
                  r"_halberd|_cutlass|_longsword|_twinblade|_chakram|_warglaive|_sai|_cap|_tunic|_pants)$")
SIMPLYSWORDS_NON_WEAPONS = re.compile(r"simplyswords:(runic_tablet|.*_gem|.*_ingot|.*_nugget|.*_block|.*_upgrade)")
DATA = re.compile(r"^data/([^/]+)/(loot_table|tags/item|gems|rarities|purity_weights)/(.+)\.json$")


def is_gear(item):
    if item.startswith("simplyswords:"):
        return not SIMPLYSWORDS_NON_WEAPONS.match(item)
    return bool(GEAR.search(item))


class Data:
    def __init__(self, root):
        self.tables, tags, self.gems, self.rarities, self.purity = {}, {}, {}, {}, {}
        root = Path(root)
        srcs = sorted(root.glob("libraries/net/minecraft/server/*/server-*-extra.jar"))  # vanilla data
        srcs += sorted((root / "mods").glob("*.jar"))
        for sub in ("global_packs/required_data", "world/datapacks"):
            if (root / sub).is_dir():
                srcs += sorted((root / sub).iterdir())
        for src in srcs:
            for name, read in self._files(src):
                m = DATA.match(name)
                if not m:
                    continue
                try:
                    obj = json.loads(read())
                except (ValueError, UnicodeDecodeError):
                    continue
                ns, kind, path = m.groups()
                rid = f"{ns}:{path}"
                if kind == "loot_table":
                    self.tables[rid] = obj
                elif kind == "tags/item":
                    vals = [v if isinstance(v, str) else v.get("id") for v in obj.get("values", [])]
                    tags[rid] = vals if obj.get("replace") else tags.get(rid, []) + vals
                elif kind == "gems" and ns == "apotheosis":
                    self.gems[rid] = obj
                elif kind == "rarities" and ns == "apotheosis":
                    self.rarities[path] = obj["weights"]
                elif kind == "purity_weights" and ns == "apotheosis":
                    self.purity = obj["weights"]
        self.tags = tags

    @staticmethod
    def _files(src):
        if src.is_dir():
            for dp, _, fn in os.walk(src):
                for f in fn:
                    full = Path(dp) / f
                    yield str(full.relative_to(src)), full.read_bytes
        elif src.suffix in (".jar", ".zip"):
            try:
                z = zipfile.ZipFile(src)
            except zipfile.BadZipFile:
                return
            for n in z.namelist():
                if n.startswith("data/"):
                    yield n, (lambda n=n: z.read(n))

    def tag_items(self, tag, seen=None):
        seen = seen or set()
        out = []
        for v in self.tags.get(tag, []):
            if v is None:
                continue
            if v.startswith("#"):
                if v[1:] not in seen:
                    seen.add(v[1:])
                    out += self.tag_items(v[1:], seen)
            else:
                out.append(v)
        return out


def tw(spec, tier, luck):
    """Placebo TieredWeights: one {weight, quality} for all tiers, or one per tier (missing tier = 0)."""
    w = spec.get(tier, {"weight": 0}) if "weight" not in spec else spec
    return max(0, math.floor(w.get("weight", 0) + w.get("quality", 0.0) * luck))


class Roller:
    def __init__(self, data, luck, tier, dim, rng):
        self.d, self.luck, self.tier, self.dim, self.rng = data, luck, tier, dim, rng
        self.unknown = Counter()

    # number providers
    def num(self, p):
        if isinstance(p, (int, float)):
            return float(p)
        t = p.get("type", "minecraft:uniform" if "min" in p else "minecraft:constant").split(":")[-1]
        if t == "constant":
            return float(p["value"])
        if t == "uniform":
            lo, hi = self.num(p["min"]), self.num(p["max"])
            return lo + self.rng.random() * (hi - lo)
        if t == "binomial":
            n, pr = int(round(self.num(p["n"]))), self.num(p["p"])
            return float(sum(self.rng.random() < pr for _ in range(n)))
        self.unknown["number:" + t] += 1
        return 1.0

    def int_(self, p):
        if isinstance(p, dict) and p.get("type", "minecraft:uniform" if "min" in p else "").endswith("uniform"):
            lo, hi = round(self.num(p["min"])), round(self.num(p["max"]))
            return self.rng.randint(lo, hi) if hi >= lo else lo
        return int(round(self.num(p)))

    # conditions
    def cond(self, c):
        t = c["condition"].split(":")[-1]
        if t == "random_chance":
            return self.rng.random() < self.num(c["chance"])
        if t == "random_chance_with_enchanted_bonus":
            return self.rng.random() < self.num(c.get("unenchanted_chance", 0))
        if t == "inverted":
            return not self.cond(c["term"])
        if t in ("any_of", "alternative"):
            return any(self.cond(x) for x in c["terms"])
        if t == "all_of":
            return all(self.cond(x) for x in c["terms"])
        if t == "artifact_rarity_adjusted_chance":
            return self.rng.random() < c["default_probability"]  # artifactRarity = 1.0 on the server
        if t == "table_bonus":
            return self.rng.random() < c["chances"][0]
        if t in ("survives_explosion", "location_check", "weather_check", "time_check", "value_check",
                 "mod_loaded", "loot_table_id", "load_conditions"):
            return True
        if t == "world_tier":
            lo, hi = c.get("min", "haven"), c.get("max", "pinnacle")
            return WORLD_TIERS.index(lo) <= WORLD_TIERS.index(self.tier) <= WORLD_TIERS.index(hi)
        if t not in ("killed_by_player", "match_tool", "entity_properties", "damage_source_properties",
                     "block_state_property", "enchantment_active_check", "entity_scores"):
            self.unknown["condition:" + c["condition"]] += 1
        return False

    def conds(self, cs):
        return all(self.cond(c) for c in cs or [])

    # entries
    def expand(self, e, out):
        """Vanilla LootPoolEntryContainer.expand: composites resolve into singleton entries."""
        if not self.conds(e.get("conditions")):
            return False
        t = e["type"].split(":")[-1]
        if t == "alternatives":
            for ch in e["children"]:
                if self.expand(ch, out):
                    return True
            return False
        if t == "group":
            for ch in e["children"]:
                self.expand(ch, out)
            return True
        if t == "sequence":
            for ch in e["children"]:
                if not self.expand(ch, out):
                    break
            return True
        if t == "tag" and e.get("expand"):
            for item in self.d.tag_items(e["name"]):
                out.append(({"type": "minecraft:item", "name": item, "functions": e.get("functions", [])},
                            e.get("weight", 1), e.get("quality", 0)))
            return True
        out.append((e, e.get("weight", 1), e.get("quality", 0)))
        return True

    def create(self, e, sink, depth):
        t = e["type"]
        short = t.split(":")[-1]
        if short == "item":
            st = [{"id": e["name"], "n": 1}]
        elif short == "tag":
            st = [{"id": i, "n": 1} for i in self.d.tag_items(e["name"])]
        elif short == "empty" or short == "dynamic":
            return
        elif short == "loot_table":
            v = e.get("value", e.get("name"))
            st = []
            if isinstance(v, str):
                tbl = self.d.tables.get(v)
                if tbl is None:
                    self.unknown["missing table:" + v] += 1
                elif depth < 8:
                    self.table(tbl, st.append, depth + 1)
            elif depth < 8:
                self.table(v, st.append, depth + 1)
        elif t == "placebo:stack_entry":
            s = e.get("stack", {})
            st = [{"id": s.get("id", s.get("item", "?")), "n": s.get("count", 1)}]
        elif t == "apotheosis:random_gem":
            st = [self.gem(e.get("purities"), e.get("gems"))]
        elif t == "apotheosis:random_affix_item":
            st = [{"id": "affix_item", "n": 1, "affix": self.rarity(e.get("rarities")), "src": "entry"}]
        else:
            self.unknown["entry:" + t] += 1
            st = [{"id": t, "n": 1}]
        for s in st:
            self.funcs(e.get("functions"), s)
            if s["n"] > 0:
                sink(s)

    def funcs(self, fs, s):
        for f in fs or []:
            if not self.conds(f.get("conditions")):
                continue
            t = f["function"].split(":")[-1]
            if t == "set_count":
                n = self.int_(f["count"])
                s["n"] = s["n"] + n if f.get("add") else n
            elif t == "limit_count":
                lim = f.get("limit", {})
                if "max" in lim:
                    s["n"] = min(s["n"], self.int_(lim["max"]))
            elif t in ("enchant_with_levels", "enchant_randomly", "set_components", "set_name", "set_lore",
                       "set_damage", "set_potion", "set_nbt", "set_custom_data", "set_stew_effect",
                       "exploration_map", "set_enchantments", "copy_components", "set_instrument",
                       "set_ominous_bottle_amplifier", "toggle_tooltips", "set_item", "set_book_cover",
                       "set_written_book_pages", "set_writable_book_pages", "set_fireworks", "set_banner_pattern",
                       "furnace_smelt", "enchanted_count_increase", "looting_enchant", "set_contents",
                       "reference", "explosion_decay", "apply_bonus", "copy_name", "set_attributes",
                       "random_item", "random_potion", "suspicious_stew", "random_dye", "random_suspicious_stew"):
                pass
            else:
                self.unknown["function:" + f["function"]] += 1

    def table(self, tbl, sink, depth=0):
        for pool in tbl.get("pools", []):
            if not self.conds(pool.get("conditions")):
                continue
            items = []
            n = self.int_(pool.get("rolls", 1)) + math.floor(self.num(pool.get("bonus_rolls", 0)) * self.luck)
            for _ in range(n):
                cands = []
                for e in pool.get("entries", []):
                    self.expand(e, cands)
                ws = [max(0, math.floor(w + q * self.luck)) for _, w, q in cands]
                tot = sum(ws)
                if not cands or tot <= 0:
                    continue
                if len(cands) == 1:
                    pick = cands[0][0]
                else:
                    r = self.rng.randrange(tot)
                    for (ent, _, _), w in zip(cands, ws):
                        r -= w
                        if r < 0:
                            pick = ent
                            break
                self.create(pick, items.append, depth)
            for s in items:
                self.funcs(pool.get("functions"), s)
                sink(s)

    # Apotheosis
    def rarity(self, allowed=None):
        names = [r.split(":")[-1] for r in allowed] if allowed else RARITIES
        ws = [tw(self.d.rarities[r], self.tier, self.luck) for r in names]
        if sum(ws) == 0:
            return "none"
        return self.rng.choices(names, ws)[0]

    def gem(self, purities=None, gems=None):
        pool = {g: v for g, v in self.d.gems.items() if not gems or g in gems}
        ws = [tw(v["weights"], self.tier, self.luck) for v in pool.values()]
        names = list(pool)
        g = self.rng.choices(names, ws)[0] if sum(ws) else names[0]
        ps = [p.split(":")[-1] for p in purities] if purities else PURITIES
        pw = [tw(self.d.purity[self.tier].get(p, {"weight": 0}), self.tier, self.luck) for p in ps]
        p = self.rng.choices(ps, pw)[0] if sum(pw) else "cracked"
        mp = pool[g].get("min_purity", "cracked")
        p = PURITIES[max(PURITIES.index(p), PURITIES.index(mp))]
        return {"id": "apotheosis:gem", "n": 1, "gem": g, "purity": p}

    def open_bag(self, table_id):
        out = []
        self.table(self.d.tables[table_id], out.append)
        # affix_conversion GLM on the bag's own table id: 35% per affixable stack without affixes.
        for s in out:
            if "affix" not in s and "gem" not in s and is_gear(s["id"]) and self.rng.random() <= 0.35:
                s["affix"], s["src"] = self.rarity(), "converted"
        return out


def summarize(data, tier_table, luck, wtier, dim, n, seed=1):
    r = Roller(data, luck, wtier, dim, random.Random(seed))
    gems, purity, affix, rar, src, notable, bags = 0, Counter(), 0, Counter(), Counter(), Counter(), Counter()
    for _ in range(n):
        for s in r.open_bag(tier_table):
            if "gem" in s:
                gems += s["n"]; purity[s["purity"]] += s["n"]
            elif "affix" in s:
                affix += 1; rar[s["affix"]] += 1; src[s["src"]] += 1
            if s["id"] in NOTABLE:
                notable[s["id"]] += s["n"]
            if s["id"].startswith("simplyswords:") and is_gear(s["id"]):
                notable["simplyswords uniques"] += s["n"]
            if s["id"].startswith("mob_loot_bags:") and s["id"].endswith("lootbag"):
                bags[s["id"].split(":")[1].replace("lootbag", "")] += s["n"]
    return {
        "gems": gems / n, "purity": {p: purity[p] / n for p in PURITIES},
        "affix": affix / n, "rarity": {k: rar[k] / n for k in RARITIES}, "affix_source": {k: v / n for k, v in src.items()},
        "notable": {k: v / n for k, v in notable.most_common()}, "bags": {k: v / n for k, v in bags.items()},
        "unknown": dict(r.unknown.most_common(8)),
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", required=True)
    ap.add_argument("--luck", type=float, action="append")
    ap.add_argument("--tier", action="append", choices=WORLD_TIERS)
    ap.add_argument("--dim", default="overworld", choices=["overworld", "the_nether", "the_end"])
    ap.add_argument("--bags", type=int, default=4000, help="bags opened per tier")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    data = Data(a.data)
    lucks, tiers = a.luck or [0.0, 7.0], a.tier or ["haven", "ascent"]
    profiles = list(zip(lucks, tiers)) if len(lucks) == len(tiers) else [(l, t) for l in lucks for t in tiers]
    result = {}
    for luck, wt in profiles:
        key = f"luck {luck:g} / {wt}"
        result[key] = {}
        for t in TIERS:
            tid = f"mob_loot_bags:bags/{t}" + ("" if a.dim == "overworld" else f"/{a.dim}")
            if tid not in data.tables:
                tid = f"mob_loot_bags:bags/{t}"
            result[key][t] = summarize(data, tid, luck, wt, a.dim, a.bags)
    if a.json:
        json.dump(result, sys.stdout, indent=1)
        return
    for key, per in result.items():
        print(f"\n== {key}, {a.dim}: per bag opened")
        print(f"{'tier':<10}{'gems':>6}  {'cracked..perfect':<34}{'affix':>6}  {'common..mythic (rarity)':<30}  sources")
        for t in TIERS:
            s = per[t]
            pur = " ".join(f"{s['purity'][p]:.2f}" for p in PURITIES)
            rar = " ".join(f"{s['rarity'][k]:.2f}" for k in RARITIES)
            srcs = " ".join(f"{k} {v:.2f}" for k, v in s["affix_source"].items())
            print(f"{t:<10}{s['gems']:6.2f}  {pur:<34}{s['affix']:6.2f}  {rar:<30}  {srcs}")
        print("notable items per bag:")
        for t in TIERS:
            s = per[t]
            print(f"  {t:<10}" + ", ".join(f"{k.split(':')[-1]} {v:.2f}" for k, v in list(s["notable"].items())[:8])
                  + (f"; bags inside: " + ", ".join(f"{k} {v:.3f}" for k, v in s["bags"].items()) if s["bags"] else ""))
        unk = Counter()
        for t in TIERS:
            unk.update(per[t]["unknown"])
        if unk:
            print("  unmodelled (counts):", dict(unk.most_common(10)))


if __name__ == "__main__":
    main()
