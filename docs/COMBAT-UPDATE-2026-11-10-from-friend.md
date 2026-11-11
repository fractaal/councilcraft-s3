# Exosphere Aero combat update — 2026-11-10

This is an overlay update for an existing **BM Exosphere 2 Aero Clean** instance running Minecraft 1.21.1 / NeoForge 21.1.262. It is not a standalone modpack and should not be imported as a new Prism instance.

## Installation

1. Close Minecraft and make a backup of the receiving instance.
2. Open the receiving instance folder: the folder containing `instance.cfg`, `mmc-pack.json`, and `minecraft`.
3. Extract this ZIP into that folder and allow the included files to replace files at the same paths. The ZIP's `minecraft/mods` must merge into the instance's existing `minecraft/mods`.
4. Launch normally. Install the same update on all players' clients and the server. On a dedicated server, copy the contents of the ZIP's `minecraft` folder into the server game directory instead.

No existing files need deletion for this update. If the receiving instance already has a DIFFERENT version of Hordium, EC2 Core, EC2 Default Lunar Events, CorgiLib or Data Anchor, keep only the included version of that mod. The original Enhanced Celestials (EC1, mod ID `enhancedcelestials`) cannot coexist with EC2. The source instance had none of those mods before this update.

Requires the existing pack's Global Packs setup: both `global_packs/required_data/` and `global_packs/required_resources/` are already configured for automatic loading. This overlay does not change that configuration.

## Added mods

| Mod | Included version |
|---|---|
| Hordium | 1.0.3 |
| Enhanced Celestials 2: Core | 2.0.3.4 NeoForge |
| Enhanced Celestials 2: Default Lunar Events | 2.0.1.4 NeoForge |
| CorgiLib (required by EC2) | 5.0.0.10 NeoForge |
| Data Anchor (required by EC2) | 2.0.0.18 NeoForge |

All five jars are the official Modrinth 1.21.1 NeoForge files. Their SHA-512 hashes match their published version metadata. Exact download URLs and hashes are included in `UPDATE-MANIFEST.json`.

## Crafted Gateways

The new Gate Pearls appear in JEI. Each has four waves, XP rewards per wave, one modest completion supply table, and a 15-second Weakness penalty for failure. Failed Gateways remove their own remaining enemies. Build a broad, enclosed arena with room for large enemies, away from valuable buildings and island edges.

All five recipes use the pattern `TMT / MAM / TMT`: four theme items (T), four material items (M), and an Eye of Ender (A).

| Gateway | Theme / material | Enemies and final-wave anchor |
|---|---|---|
| Sky Raiders | Crossbow / iron ingot | Pillagers, vindicators, Raided illagers; Magehunter Vindicator finale |
| Restless Ossuary | Bone block / iron ingot | Skeletons, Koboletons, a Necromancer; Kobolediator finale |
| Frostbound Host | Blue ice / amethyst shard | Draugr variants, strays, Ice Spiders, Iceologer; Aptrgangr finale |
| Cinder Foundry | Blaze rod / gold ingot | Wither skeletons, blazes, Ignited Revenants; Ignited Berserker finale |
| Rogue Constructs | Echo shard / Create brass ingot | Watchers and Prowlers; Ender Golem finale |

The last four include Cataclysm minibosses, not the mod's major structure bosses. Gateway definitions are in `global_packs/required_data/Aero Combat/data/aero_combat/gateways/`.

## Hordium encounters

| Encounter ID | First eligible world day | Automatic location |
|---|---:|---|
| `aero_combat:gravebound_scouts` | 7 | Overworld |
| `aero_combat:sky_raiders` | 14 | Overworld |
| `aero_combat:dune_revenants` | 14 | Vanilla desert/badlands biomes |
| `aero_combat:frostbound_warband` | 20 | Listed vanilla snowy/mountain biomes |
| `aero_combat:cinder_patrol` | 14 in definition | Manual only; designed for the Nether |

Each encounter has three waves with two alternative compositions per wave. Automatic waves contain at most 10 enemies before any summoned creatures or splitting mobs. Composition and reward details live in the JSON definitions.

Automatic raids use moon phases 2 and 6 and require visible sky. Hordium rolls a 3% chance per eligible player every 60 seconds, with one concurrent automatic raid per dimension. This is a probability, not a fixed interval or a guaranteed cooldown. The mod's native random check does not restrict events to nighttime or exclude creative players. Events can start during the day on those moon phases. Operators can bypass restrictions through manual start commands.

Spawn radius is 24 blocks instead of the author's sample 64, with terrain-aware surface spawning. Very small islands and steep terrain still require testing; attackers can land on separated ground or rooftops. The radius is not a guarantee of a reachable path.

Raid expiration is 10 minutes; abandonment after players leave the tracking area is 60 seconds. Native passive expiration stops subsequent waves but can leave spawned enemies alive. The author's three sample raids remain available through commands but have `random_eligible: false`.

Operator commands: `/hordium list`, `/hordium why aero_combat:sky_raiders`, `/hordium start aero_combat:sky_raiders`, `/hordium stop`. Commands are administrative tools; the ordinary gameplay entry points are automatic raids and crafted Gate Pearls.

## EC2 tuning

- Normal blood moon: 1.5× monster population multiplier, with six nights minimum between occurrences of that event.
- Super blood moon: 2.25× monster population multiplier; original minimum 20-night spacing and full-moon restriction retained.
- Normal blood moons exclude phases 2 and 6; automatic Overworld Hordium events use those phases. This separates automatic blood moons and Hordium raids under normal time progression. Manual commands or time changes can override that separation.
- Other EC2 lunar events retain their mod defaults. Existing In Control rules and ordinary mob caps were not edited.

## Validation and unfinished work

Validated: published jar hashes; JSON syntax for all 35 generated datapack/resource-pack files; definitions were built against the schemas inspected in the installed Hordium, Gateways and EC2 jars.

**Runtime validation is not complete.** The launch attempt stopped at Prism's failed-download retry prompt (`http://0.0.0.2`), and the user then requested this ZIP handoff. No world was opened for testing. Recipes, registry loading, mod interactions, wave completion and terrain behavior remain untested in game.

**The requested Apotheosis final-wave enhancement is not implemented in this update.** Investigation confirmed native Gateway types `apotheosis:invader` and `apotheosis:elite`, but the generated Gateways currently use ordinary modded enemies. No fabricated affix NBT or partial Apotheosis templates are shipped.

This update was compared against the Desktop export `BM - Exosphere 2 Aero (Clean) v1.5.zip` (1,138,981,386 bytes). Of the distribution files compared, 3,498 were unchanged. The overlay includes 47 new/changed payload files: the 41 combat files and six current quest definition/language files (`aero_field_guide`, `aero_workshop`, `aeronautics`, `companions`, `the_travelers_quest`, and aggregate English quest text). Existing player quest progress is not included or reset.

Two changed cache/history files are excluded. The only baseline distribution files absent locally were 270 Prism/packwiz `minecraft/mods/.index` metadata files; these are not gameplay files and need no deletion. Launcher settings, local caches and personal data are excluded. Earlier installed mods already present in v1.5 are not duplicated in the update.

## Rollback

With Minecraft closed, remove only the five added jar filenames listed in the manifest, `config/hordium-common.toml`, and the two `global_packs/.../Aero Combat` folders. Restore any replaced files from your backup if this update was applied previously. No saves, player data, quest progress, launcher settings or unrelated configuration are included in this ZIP.

Project sources: https://modrinth.com/mod/hordium ; https://modrinth.com/mod/enhanced-celestials-2-default-lunar-events ; https://modrinth.com/mod/enhanced-celestials-2-core ; https://modrinth.com/mod/corgilib ; https://modrinth.com/mod/data-anchor
