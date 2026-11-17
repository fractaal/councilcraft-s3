#!/usr/bin/env python3
"""Loot drop simulator for Mob Loot Bags 2.0.1 and FTB Quests loot crates.

Reproduces the two mods' drop rolls (decompiled from mob_loot_bags-neoforge-1.21.1-2.0.1's DropEngine and TierRoller,
and ftb-quests-neoforge-2101.1.36's BaseQuestFile.makeRandomLootCrate) and runs them over a kill mix, so a config change
can be checked before it goes live.

  python3 sim.py --server DIR                         reference profiles over a real player's kill mix
  python3 sim.py --server DIR --luck 25 --looting 3   one profile
  python3 sim.py --server DIR --validate              predicted vs actual bags found, per player
  python3 sim.py --server DIR --set luck.luck_attribute=0.25   try a config change
  python3 sim.py --server DIR --luck-bags off                 leave out the skyislands LuckBags bonus stacks
  python3 sim.py --server DIR --contents FILE                 add power columns from bag_contents.py --json

DIR is a server folder (or a copy) holding config/mob_loot_bags/drops.json, config/ftbquests/quests/ and, for kill
mixes and --validate, world/stats/*.json, world/data/mob_loot_bags/players.json and usercache.json.

Hourglass bags are counted as the two bags they ripen into (mob_loot_bags:bags/hourglass: 2 rolls, from the Aero Loot
Bags datapack in DIR's global_packs if it overrides the table, else the mod jar's epic 60 / legendary 30 / mythic 10;
--hourglass overrides both), not as a bag of their own. Luck bags (the skyislands mod's LuckBags, on since 2026-11-16)
are on by default: a hostile, non-boss kill by a real player also drops one LUCK_STACKS stack with chance
min(0.5, 0.003 x Luck), times SPAWNER_SHARE from spawner mobs. The gems column is
Apotheosis 8.9.0's mob gem drops: gem_entity_drops (0.5%, killed_by_player) plus gem_entity_drops_from_real_players
(4%), both only for `instanceof Monster` victims, so slimes, ghasts, phantoms, shulkers and hoglins give none.

What it doesn't model: cursed-bag ambush rewards and jackpots, bags found inside bags, per-dimension themes, and
spawner tags (pass --spawner for the share of kills from spawners). Mob max health comes from the table below, measured
on the real server; mobs not in it count as hostile with 20 health, and the share of such kills is printed. Apotheosis world tier
plays no part in either mod's rolls.
"""
import argparse, json, math, random, re, sys
from collections import Counter
from pathlib import Path

TIERS = ["common", "uncommon", "rare", "epic", "legendary", "mythic"]
SPECIALS = ["cursed", "hourglass", "locked", "summoning"]

# Max health as mobs really spawn on this server: measured in the lab with /lootcheck (2026-11-16), which reads it
# after every mod's spawn-time changes. Several differ from vanilla here (zombies, pillagers and their kin have 24,
# skeletons 22.3, piglins 20). Slimes and magma cubes are averages over their sizes. The last three lines are vanilla
# values that were not measured. Mob Loot Bags scales a mob's bag chance by health / 20.
MOBS = {
    "minecraft:zombie": 24.1, "minecraft:husk": 24.2, "minecraft:drowned": 24.1, "minecraft:zombie_villager": 24.3,
    "minecraft:skeleton": 22.3, "minecraft:stray": 22.4, "minecraft:bogged": 16, "minecraft:wither_skeleton": 20.1,
    "minecraft:spider": 16, "minecraft:cave_spider": 12, "minecraft:creeper": 20, "minecraft:enderman": 40.3,
    "minecraft:witch": 26.1, "minecraft:slime": 6.4, "minecraft:magma_cube": 7.2, "minecraft:blaze": 20,
    "minecraft:ghast": 10, "minecraft:piglin": 20, "minecraft:piglin_brute": 50.2, "minecraft:zombified_piglin": 24.1,
    "minecraft:hoglin": 40, "minecraft:zoglin": 40, "minecraft:pillager": 24.1, "minecraft:vindicator": 24.1,
    "minecraft:evoker": 24.2, "minecraft:vex": 14.1, "minecraft:ravager": 100, "minecraft:phantom": 20,
    "minecraft:guardian": 30, "minecraft:elder_guardian": 80, "minecraft:shulker": 30, "minecraft:silverfish": 8,
    "minecraft:endermite": 8, "minecraft:breeze": 30, "minecraft:warden": 500,
    "irons_spellbooks:necromancer": 25, "irons_spellbooks:citadel_keeper": 60, "irons_spellbooks:cursed_armor_stand": 60,
    "royalvariations:royal_zombie": 60, "royalvariations:royal_skeleton": 45, "cataclysm:koboleton": 25,
    "minecraft:wither": 300, "minecraft:ender_dragon": 200, "minecraft:illusioner": 32,
}
PASSIVE = {"minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken", "minecraft:rabbit",
           "minecraft:villager", "minecraft:iron_golem", "minecraft:snow_golem", "minecraft:fox", "minecraft:bee",
           "minecraft:squid", "minecraft:glow_squid", "minecraft:cod", "minecraft:salmon", "minecraft:bat",
           "naturalist:butterfly", "minecraft:wandering_trader", "minecraft:goat", "minecraft:wolf",
           "modulargolems:humanoid_golem"}
# The mob_loot_bags:bosses tag (plus anything in c:bosses). The skyislands mod makes FTB Quests crates use it too.
BAG_BOSSES = {"minecraft:wither", "minecraft:ender_dragon", "minecraft:warden", "minecraft:elder_guardian"}
# skyislands.LootRules: bags count a player's Luck up to this, and spawner mobs keep this share of their crates and gems.
BAG_LUCK_CAP = 30.0
SPAWNER_SHARE = 1.0  # 0.25 until 2026-11-17, when fractal took the spawner penalty off
# Hostile in MOBS but not a net.minecraft.world.entity.monster.Monster subclass, so no Apotheosis mob gems.
NOT_MONSTER = {"minecraft:slime", "minecraft:magma_cube", "minecraft:ghast", "minecraft:phantom", "minecraft:shulker",
               "minecraft:hoglin", "minecraft:ender_dragon", "irons_spellbooks:necromancer",
               "irons_spellbooks:citadel_keeper", "irons_spellbooks:cursed_armor_stand"}
# The mob_loot_bags:bag_carriers tag: a natural spawn of one of these holds a loot bag "carriers.chance" of the time and
# always drops it. The bag's tier is rolled when the mob spawns, with "carriers.luck" and no player's Luck. A carried bag
# is not counted in the mod's per-player "found" numbers.
BAG_CARRIERS = {"minecraft:zombie", "minecraft:husk", "minecraft:skeleton", "minecraft:stray", "minecraft:piglin",
                "minecraft:zombified_piglin", "minecraft:pillager", "minecraft:vindicator", "minecraft:wither_skeleton",
                "minecraft:bogged"}
GEM_CHANCE = 0.005 + 0.04
HOURGLASS = {"epic": 60, "legendary": 30, "mythic": 10}  # the mod jar's own table
HOURGLASS_OVERRIDE = "global_packs/required_data/Aero Loot Bags/data/mob_loot_bags/loot_table/bags/hourglass.json"
LUCK_BAGS = (0.003, 0.5)  # skyislands LuckBags.RATE and CAP
# skyislands LuckBags.STACKS (see --luck-bags): one bonus stack per success, tier by weight, count range.
LUCK_STACKS = [("common", 60, 3, 5), ("uncommon", 28, 2, 3), ("rare", 10, 1, 2), ("epic", 2, 1, 1)]


def parse_luck_bags(arg):
    if arg is None:
        return LUCK_BAGS
    if arg == "off":
        return None
    parts = [float(x) for x in arg.split(",")]
    return (parts[0], parts[1] if len(parts) > 1 else LUCK_BAGS[1])


def load_hourglass(server):
    """The hourglass bag's two-roll pool: the datapack override in the server folder if there is one, else the jar's."""
    path = Path(server) / HOURGLASS_OVERRIDE
    if not path.exists():
        return HOURGLASS
    pool = json.loads(path.read_text())["pools"][0]
    return {e["name"].split(":")[1].removesuffix("lootbag"): e.get("weight", 1) for e in pool["entries"]}


def load_jsonc(path):
    text = re.sub(r"^\s*//.*$", "", Path(path).read_text(), flags=re.M)
    return json.loads(text)


def apply_set(cfg, assignments):
    for a in assignments or []:
        key, val = a.split("=", 1)
        node = cfg
        parts = key.split(".")
        for p in parts[:-1]:
            node = node[p]
        node[parts[-1]] = json.loads(val)


def load_crates(server):
    """[(string_id, monster, boss, passive)] plus the no-drop weights and the master switch, from the quest files."""
    q = Path(server) / "config/ftbquests/quests"
    data = (q / "data.snbt").read_text()
    on = "drop_loot_crates: true" in data
    nd = re.search(r"loot_crate_no_drop: \{\s*boss: (\d+)\s*monster: (\d+)\s*passive: (\d+)", data)
    no_drop = {"boss": int(nd[1]), "monster": int(nd[2]), "passive": int(nd[3])}
    crates = []
    for f in sorted((q / "reward_tables").glob("*.snbt")):
        t = f.read_text()
        m = re.search(r"drops: \{\s*boss: (\d+)\s*monster: (\d+)\s*passive: (\d+)", t)
        if not m:
            continue
        sid = re.search(r'string_id: "([^"]+)"', t)
        crates.append((sid[1] if sid else f.stem, {"boss": int(m[1]), "monster": int(m[2]), "passive": int(m[3])}))
    return on, no_drop, crates


class Profile:
    def __init__(self, name, luck=0.0, looting=0, talisman=False, dim="overworld", hard=False, spawner=0.0):
        self.name, self.luck, self.looting, self.talisman = name, luck, looting, talisman
        self.dim, self.hard, self.spawner = dim, hard, spawner


def mob_info(mob):
    passive = mob in PASSIVE
    known = passive or mob in MOBS
    return MOBS.get(mob, 20.0), passive, known


def bag_luck(cfg, p, health):
    L, hs = cfg["luck"], cfg["health_scaling"]
    luck = p.looting * L["per_looting_level"] + min(p.luck, BAG_LUCK_CAP) * L["luck_attribute"] + (L["fortune_talisman"] if p.talisman else 0)
    if hs["enabled"] and health > hs["reference_health"]:
        luck += min(max(math.log2(health / hs["reference_health"]), 0), L["max_health_luck"])
    luck += {"nether": L["nether"], "end": L["end"]}.get(p.dim, 0.0)
    if p.hard:
        luck += L["hard_difficulty"]
    return luck


def tier_dist(cfg, luck, min_rank=0):
    w = [cfg["tier_weights"][t] * math.exp(cfg["luck"]["per_point"] * luck * i) if i >= min_rank else 0.0
         for i, t in enumerate(TIERS)]
    s = sum(w)
    return [x / s for x in w]


def bag_chance(cfg, mob, health, passive, from_spawner, p):
    c = cfg["passive_chance"] if passive else cfg["hostile_chance"]
    c *= cfg["mob_multipliers"].get(mob, 1.0)
    hs = cfg["health_scaling"]
    if hs["enabled"]:
        c *= min(max(health / hs["reference_health"], hs["min_multiplier"]), hs["max_multiplier"])
    if from_spawner:
        c *= cfg["spawner_multiplier"]
    if p.talisman:
        c *= cfg["talisman_chance_multiplier"]
    return min(1.0, c * cfg["global_multiplier"])


def pick(rng, dist):
    r, acc = rng.random(), 0.0
    for i, x in enumerate(dist):
        acc += x
        if r < acc:
            return i
    return len(dist) - 1


def simulate(cfg, crates_cfg, mix, p, kills, seed=1, hourglass=HOURGLASS, luck_bags=None, carriers=True):
    """Monte Carlo over `kills` kills drawn from `mix` (Counter of mob id -> count), with pity, specials and bosses.

    luck_bags = (rate, cap): skyislands LuckBags. On every hostile, non-boss kill that could drop a bag, with chance
    min(cap, rate * Luck attribute) (times spawner_multiplier for spawner mobs) it also drops one LUCK_STACKS stack.
    carriers=False leaves out the bags that carrier mobs hold, to compare with the mod's "found" counters.
    """
    rng = random.Random(seed)
    hg = [(TIERS.index(t), w) for t, w in hourglass.items()]
    hg_total = sum(w for _, w in hg)
    extra = Counter()
    mobs, weights = zip(*mix.items())
    bags, crates = Counter(), Counter()
    pity = {"kills": 0, "rare": 0, "leg": 0}
    pc = cfg["pity"]; sp = cfg["specials"]; boss = cfg["bosses"]
    on, no_drop, crate_list = crates_cfg
    cum, acc = [], 0
    for w in weights:
        acc += w; cum.append(acc)
    import bisect
    for _ in range(kills):
        mob = mobs[bisect.bisect_right(cum, rng.random() * acc)]
        health, passive, _known = mob_info(mob)
        luck = bag_luck(cfg, p, health)
        from_spawner = rng.random() < p.spawner
        if (not passive and mob not in NOT_MONSTER and rng.random() < GEM_CHANCE
                and (not from_spawner or rng.random() < SPAWNER_SHARE)):
            extra["gems"] += 1
        if carriers and cfg["enabled"] and mob in BAG_CARRIERS and not from_spawner and rng.random() < cfg["carriers"]["chance"]:
            nobody = Profile("", 0.0, 0, False, p.dim, p.hard)
            bags[TIERS[pick(rng, tier_dist(cfg, cfg["carriers"]["luck"] + bag_luck(cfg, nobody, health)))]] += 1
            extra["carried"] += 1
        # FTB Quests crate roll, independent of bags. A spawner mob keeps its crate a quarter of the time.
        if on and (not from_spawner or rng.random() < SPAWNER_SHARE):
            kind = "boss" if mob in BAG_BOSSES else ("passive" if passive else "monster")
            total = no_drop[kind] + sum(c[1][kind] for c in crate_list)
            if total > 0:
                n = rng.randrange(total) + 1
                cur = no_drop[kind]
                if cur < n:
                    for sid, w in crate_list:
                        cur += w[kind]
                        if cur >= n:
                            crates[sid] += 1
                            break
        if not cfg["enabled"]:
            continue
        if mob in BAG_BOSSES:
            mr = TIERS.index(boss["min_tier"])
            for _ in range(boss["bags"]):
                t = 5 if rng.random() < boss["mythic_chance"] else pick(rng, tier_dist(cfg, luck, mr))
                bags[TIERS[t]] += 1
            continue
        chance = bag_chance(cfg, mob, health, passive, from_spawner, p)
        if chance <= 0:
            continue
        if luck_bags and not passive:
            lb = min(luck_bags[1], luck_bags[0] * max(0.0, p.luck)) * (cfg["spawner_multiplier"] if from_spawner else 1)
            if rng.random() < lb:
                r = rng.random() * sum(s[1] for s in LUCK_STACKS)
                for tier, w, lo, hi in LUCK_STACKS:
                    r -= w
                    if r < 0:
                        n = rng.randint(lo, hi)
                        bags[tier] += n
                        extra["luck_" + tier] += n
                        break
        forced = False
        if pc["enabled"]:
            pity["kills"] += 1
            forced = pc["kills_without_bag"] > 0 and pity["kills"] >= pc["kills_without_bag"]
        if not forced and rng.random() >= chance:
            continue
        pity["kills"] = 0
        special = None
        for s in SPECIALS:
            if rng.random() < sp[s]:
                special = s
                break
        if special == "hourglass":
            extra["hourglass"] += 1
            for _ in range(2):
                r = rng.random() * hg_total
                for t, w in hg:
                    r -= w
                    if r < 0:
                        bags[TIERS[t]] += 1
                        break
            continue
        if special:
            bags[special] += 1
            continue
        mr = 0
        if pc["enabled"]:
            if pc["bags_until_legendary"] > 0 and pity["leg"] >= pc["bags_until_legendary"]:
                mr = 4
            elif pc["bags_until_rare"] > 0 and pity["rare"] >= pc["bags_until_rare"]:
                mr = 2
        t = pick(rng, tier_dist(cfg, luck, mr))
        bags[TIERS[t]] += 1
        pity["rare"] = 0 if t >= 2 else pity["rare"] + 1
        pity["leg"] = 0 if t >= 4 else pity["leg"] + 1
    bags.update({"_" + k: v for k, v in extra.items()})
    return bags, crates


def kill_mix(server, uuid):
    stats = json.loads((Path(server) / "world/stats" / f"{uuid}.json").read_text())["stats"]
    return Counter(stats.get("minecraft:killed", {})), stats.get("minecraft:custom", {})


def names(server):
    try:
        return {e["uuid"]: e["name"] for e in json.loads((Path(server) / "usercache.json").read_text())}
    except FileNotFoundError:
        return {}


def power(contents, luck, bags):
    """Gems, affix items and nether stars from opening the bags, using the bag_contents.py profile nearest `luck`."""
    key = min(contents, key=lambda k: abs(float(k.split()[1]) - luck))
    per = contents[key]
    return key, {
        "gems": sum(bags[t] * per[t]["gems"] for t in TIERS),
        "affix": sum(bags[t] * per[t]["affix"] for t in TIERS),
        "stars": sum(bags[t] * per[t]["notable"].get("minecraft:nether_star", 0) for t in TIERS),
    }


def fmt_row(label, bags, crates, kills, contents=None, luck=0.0):
    per = 1000.0 / kills
    tiers = "  ".join(f"{bags[t] * per:7.1f}" for t in TIERS)
    total = sum(bags[t] for t in TIERS + SPECIALS) * per
    # Crafting value: what every tier bag is worth in mythics through the 4-to-1 recipes (stars ignored).
    craft = sum(bags[t] * 4 ** i for i, t in enumerate(TIERS)) / 4 ** 5 * per
    row = (f"{label:<26}{tiers}  {total:7.1f}  {craft:7.2f}  {sum(crates.values()) * per:7.1f}"
           f"  {bags['_gems'] * per:7.1f}")
    if contents:
        _, pw = power(contents, luck, bags)
        row += "  " + "  ".join(f"{pw[k] * per:7.2f}" for k in ("gems", "affix", "stars"))
    return row


def header(contents=None):
    h = f"{'per 1000 kills':<26}" + "  ".join(f"{t[:7]:>7}" for t in TIERS) + \
        f"  {'all bags':>7}  {'mythEq':>7}  {'crates':>7}  {'mobgems':>7}"
    if contents:
        h += f"  {'baggems':>7}  {'affix':>7}  {'stars':>7}"
    return h


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--server", required=True)
    ap.add_argument("--mix-from", default="otenlamiughh", help="player whose real kill mix is used")
    ap.add_argument("--kills", type=int, default=200000)
    ap.add_argument("--luck", type=float); ap.add_argument("--looting", type=int, default=0)
    ap.add_argument("--talisman", action="store_true"); ap.add_argument("--hard", action="store_true")
    ap.add_argument("--dim", default="overworld", choices=["overworld", "nether", "end"])
    ap.add_argument("--spawner", type=float, default=0.0, help="share of kills from monster spawners (0-1)")
    ap.add_argument("--set", action="append", help="override a drops.json key, e.g. luck.luck_attribute=0.25")
    ap.add_argument("--validate", action="store_true")
    ap.add_argument("--player-luck", action="append", default=[], help="NAME=LUCK for --validate")
    ap.add_argument("--crates", action="store_true", help="also list crates by type")
    ap.add_argument("--hourglass", help="hourglass bag weights, e.g. rare=60,epic=30,legendary=10")
    ap.add_argument("--luck-bags", help="RATE[,CAP]: LuckBags bonus-stack chance per kill = min(CAP, RATE*Luck); "
                    "default 0.003,0.5 as live; 'off' leaves it out")
    ap.add_argument("--contents", help="bag_contents.py --json output, for gems/affix items/nether stars from bags")
    a = ap.parse_args()
    hourglass = (dict((k, float(v)) for k, v in (x.split("=") for x in a.hourglass.split(","))) if a.hourglass
                 else load_hourglass(a.server))
    luck_bags = parse_luck_bags(a.luck_bags)
    contents = json.loads(Path(a.contents).read_text()) if a.contents else None

    cfg = load_jsonc(Path(a.server) / "config/mob_loot_bags/drops.json")
    apply_set(cfg, a.set)
    crates_cfg = load_crates(a.server)
    who = names(a.server)
    by_name = {v: k for k, v in who.items()}

    mix, custom = kill_mix(a.server, by_name.get(a.mix_from, a.mix_from))
    unknown = sum(n for m, n in mix.items() if not mob_info(m)[2])
    print(f"kill mix: {a.mix_from}, {sum(mix.values())} kills, {len(mix)} mob types, "
          f"{unknown / max(1, sum(mix.values())):.0%} not in the health table (counted as hostile, 20 health)")
    print(f"config: luck_attribute {cfg['luck']['luck_attribute']}, per_point {cfg['luck']['per_point']}, "
          f"hostile_chance {cfg['hostile_chance']}; FTB crates {'on' if crates_cfg[0] else 'off'}\n")

    if a.validate:
        luck = dict((k, float(v)) for k, v in (x.split("=") for x in a.player_luck))
        found = json.loads((Path(a.server) / "world/data/mob_loot_bags/players.json").read_text())
        print(f"{'player':<16}{'kills':>6}  {'luck':>5}  " + "  ".join(f"{t[:6]:>13}" for t in TIERS))
        for uuid, e in found.items():
            name = who.get(uuid, uuid[:8])
            try:
                pmix, pc = kill_mix(a.server, uuid)
            except FileNotFoundError:
                continue
            kills = pc.get("minecraft:mob_kills", sum(pmix.values()))
            if not pmix or kills < 50:
                continue
            p = Profile(name, luck.get(name, 0.0), a.looting, a.talisman, a.dim, a.hard, a.spawner)
            bags, _ = simulate(cfg, crates_cfg, pmix, p, kills * 20, hourglass={}, luck_bags=luck_bags, carriers=False)
            cells = "  ".join(f"{e['found'][t]:>5} vs {bags[t] / 20:5.0f}" for t in TIERS)
            print(f"{name:<16}{kills:>6}  {p.luck:>5.1f}  {cells}")
        print("\nactual vs predicted at the luck given, with today's config. Actual counts cover the whole world's"
              " history, when luck and config were different.")
        return

    if a.luck is not None:
        profiles = [Profile(f"luck {a.luck}", a.luck, a.looting, a.talisman, a.dim, a.hard, a.spawner)]
    else:
        profiles = [Profile(f"luck {l}", l, a.looting, a.talisman, a.dim, a.hard, a.spawner) for l in (0, 7, 15, 25, 30)]
    print(header(contents))
    for p in profiles:
        bags, crates = simulate(cfg, crates_cfg, mix, p, a.kills, hourglass=hourglass, luck_bags=luck_bags)
        print(fmt_row(p.name, bags, crates, a.kills, contents, p.luck))
        if a.crates:
            for sid, n in crates.most_common():
                print(f"    {sid:<28}{n * 1000.0 / a.kills:7.2f}")


if __name__ == "__main__":
    main()
