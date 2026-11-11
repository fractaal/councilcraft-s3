# CouncilCraft S3: packwiz repo

CouncilCraft season 3 runs BM Exosphere 2 Aero. This is the pack definition, on Minecraft 1.21.1 with NeoForge 21.1.262. It starts from the "BM - Exosphere 2 Aero (Clean) v1.5" build that a friend made, with their 2026-11-10 combat update on top, and adds our sky-island terrain, extra biome mods and a set of cozy mods (farming, cooking, decoration). Pack version `1.5.1+sky.2`.

It holds no mod jars except our own. Every other mod, resource pack and shader is a small `.pw.toml` file that pins an exact version and hash and says where to download it (398 from Modrinth, 119 from CurseForge). Configs, KubeJS scripts, quests and datapacks are real files in the repo.

## What is in the repo

| Path | What it is |
|---|---|
| `pack.toml`, `index.toml` | The packwiz pack and its file index. Rebuilt by `packwiz refresh`. |
| `mods/*.pw.toml` | One file per mod. `side` says where it installs: `both`, or `client` for mods the live server doesn't run (Sodium, Iris and so on). |
| `mods/skyislands-1.0.0.jar` | Our own terrain mod. The only jar in the repo. |
| `resourcepacks/`, `shaderpacks/`, `datapacks/` | Metadata for packs hosted on Modrinth, plus the few that exist nowhere else as plain files. |
| `config/`, `kubejs/`, `scripts/`, `defaultconfigs/` | Pack configuration and quests (from v1.5). `config/fml.toml` is left out on purpose, see "Server notes". |
| `global_packs/` | Datapacks and resource packs loaded by the Global Packs mod (see below). |
| `tools/skyislands-mod/` | Source for our terrain mod and a `build.sh`. |
| `tools/worldgen/` | `settings-archipelago.json` (the island settings in use), the older `settings-lakes.json` and `settings-islands.json`, and `gen_java.py`, which turns settings into the datapack's noise settings. |
| `tools/worldgen-lab/` | A test-server mod with a web viewer for tuning the terrain, plus benchmarks (`/api/tile`, `/api/fullgen`). Run it with `run-lab.sh` on a copy of a server folder, never on the live one. |
| `tools/tests/` | Scripts used to check generated worlds (structure placement, terrain comparison, water). |
| `tools/prism/` | Builds the Prism Launcher instance zip for players. |
| `docs/` | Notes from the friend's v1.5 export. |

Files in `tools/`, `docs/` and `*.md` are listed in `.packwizignore`, so installs don't copy them.

## World generation

Three datapacks touch how the world is built. They are independent, so know which ones are on.

| Pack | Where | What it does |
|---|---|---|
| **Aero Island Structures** (friend's) | `global_packs/required_data/`, always on | Cataclysm, Better Dungeons and other underground structures were being placed at fixed heights (for example y=-30) in the void under the islands. This pack only lets them generate where the world's `final_density` is solid at that spot. |
| **Aero Sky Islands** (ours) | `global_packs/optional_data/`, off by default | An archipelago: islands in a continuous range of sizes from continents to small islets, with lakes, in up to three height layers, and a taller world (build limit y=511). The small islets are spaced so most are within grapple range (64 blocks) of another. Needs `mods/skyislands-1.0.0.jar`. |
| **Aero Mainlands** (friend's, "test") | `global_packs/optional_data/`, off by default | An alternative world shape. It replaces the same `minecraft:overworld` noise settings as Aero Sky Islands, so never enable both. |

### Using Aero Sky Islands: new worlds only
Our pack changes the overworld's height and terrain. On a world that already has chunks, new chunks would not line up with old ones and there would be a hard seam at the border. Only enable it when creating a world.

- **Dedicated server:** before the first start of a new world, copy `global_packs/optional_data/Aero Sky Islands` into `<world>/datapacks/`.
- **Singleplayer:** tick it on the Data Packs screen when creating the world.

To change the islands, edit `tools/worldgen/settings-archipelago.json` and regenerate:
`python3 tools/worldgen/gen_java.py settings-archipelago.json <exosphere overworld.json> out.json`, where `overworld.json` is `data/minecraft/worldgen/noise_settings/overworld.json` from the `exosphere` mod jar. Put `out.json` in the datapack. If the Exosphere mod updates, regenerate against its new file.

## Installing

### Players (Prism Launcher)
1. Download `councilcraft-s3-prism.zip` from the [latest release](https://github.com/fractaal/councilcraft-s3/releases/latest).
2. In Prism: Add Instance, Import, and pick the zip.
3. Launch it. The first launch downloads about 1 GB of mods; if a window lists mods to download by hand, follow its links, put the files where it says, and launch again.

The instance has a pre-launch command that runs the packwiz installer against `https://raw.githubusercontent.com/fractaal/councilcraft-s3/main/pack.toml`, so every launch picks up whatever is on `main`. Memory is set to 6 to 12 GB. The zip itself never needs updating; to make it again, run `tools/prism/make-instance.sh <pack.toml URL> <label>`.

### Dedicated server
In an **empty** folder:
`java -jar packwiz-installer-bootstrap.jar -g -s server <URL of pack.toml>`

Don't run it directly over a live server folder. On a first run the installer writes the pack's version of every config file, which would replace settings tuned on the server. Install into an empty folder, diff it against the live one, and copy over what you want.

### Updating the pack
Change files or run `packwiz update <mod>`, then `packwiz refresh`, bump `version` in `pack.toml`, and publish.

## Server notes
- Keep `maxThreads = 1` in the server's `config/fml.toml`. With parallel mod construction, boot deadlocked between `libjf` and Mixin. `libjf` is no longer in the pack, but 1 is the setting every server boot has been tested with. The friend's v1.5 has `maxThreads = -1` in its client config, so the repo ships no `fml.toml` and each side keeps its own.
- Do not add Chunky or C2ME. They break against Sable's chunk hooks.
- On the server, set `timeout=15` in `config/packetfixer.properties`. The pack ships `120`, and on NeoForge Packet Fixer then sends keepalives only every 120 seconds while the server still drops a client it hasn't heard from in 30. Players get "Timed out" while JEI loads after joining.
- Sable sends physics traffic over UDP on the game port, so open the port for UDP as well as TCP.
- `ghosts` and `e4mc` are marked `client`: the live server doesn't run them today. Change their `side` if that should change.
- Distant terrain comes from Voxy, not Distant Horizons. Voxy is Fabric-only, so clients run the original Voxy jar (0.2.16-beta, built for 1.21.11) through **Roxy**, a NeoForge compatibility layer that its author has verified with Forgified Fabric API 0.116.14. **Voxy Server Side** (`side = "both"`) lets the server send distant terrain and generate missing terrain for it. Its server config is `config/vss-server-config.yaml`; it has no reload command, so changes need a restart. Its network channels are optional, so players without Voxy can still join.
- Voxy Server Side's generation loads chunks through the server the way Chunky does. It ran for 25 minutes at 40 chunks at a time on the VM (2026-11-10) without Sable errors, but keep an eye on it.

## What changed in 1.5.1+sky.2
- **Terrain:** the archipelago settings replace the lakes settings. Small islets are 1.5 times as dense as in the first archipelago test, so the median gap to the nearest islet is 20 blocks. A bug that clipped about a fifth of the islet land is fixed.
- **Biome mods:** TerraBlender, Oh The Biomes We've Gone (with Oh The Trees You'll Grow), Nature's Spirit, Geophilic, Streams Reflowing and RoadWeaver. Countered's Terrain Slabs was tried and dropped: it cost about a fifth of chunk generation time (see the speed table).
- **Streams Reflowing runs at its default settings** (`config/streamsreflowing.properties`), so its bank materials, bank plants and seagrass also dress our island lakes. Its tuned-for-speed settings (`terrainAccuracyLevel=LOW`, `adaptiveGridRead=false`, and banks and plants on its own water only) generated 10-17% faster (see the speed table) but were set back to the defaults on 2026-11-11.
- **Cozy mods:** the Let's Do family (Farm & Charm, Meadow, Vinery, Beachparty, Alpine Whispers, Camping, Furniture, Hearth & Timber, HerbalBrews, Lili's Pottery, WilderNature, Corn Expansion and the Farm & Charm compat add-ons), Farmer's Delight add-ons (Chef's Delight, End's, Expanded, Nature's and Ocean's Delight, Farmer's Knives), decoration mods (Handcrafted, Cluttered, Twigs, Macaw's Paths and Pavings, Paintings and Holidays, Fairy Lights, Fetzi's Asian Decoration, Dusty Decorations, Diagonal Windows, Sooty Chimneys, Supplementaries Squared, Dark Paintings, Perfect Plushies, Simple Hats), animal mods (Friends For Life, Icy's Better Horses, More Axolotl Variants, Animal Feeding Trough, No Animal Tempt Delay), farming tweaks (Crops Love Rain, Grass Seeds, Universal Bone Meal, Smarter Farmers), and Sit, Spud's Shops, Storage Labels, Stack Refill, Block Runner, ColoredWater, Modonomicon and Typewriter Day Counter. Client-only: 3D Skin Layers, Subtle Effects, Sound Physics Remastered, the Fresh Animations packs, Fancy Beds, Better Grass, Bushy Pink Petals, Create Touch-Ups and two shader packs.
- **Forgiving Void:** falling into the void drops you back from y=520 above your last solid ground (`config/forgivingvoid-common.toml`). Landing does damage equal to your full health (`RELATIVE_MAX`, 100%), so you survive only by landing in water, clutching a water bucket or reducing the damage (Feather Falling, Protection). A gravestone keeps your items if you die.
- **Removed:** `libjf` and Respackopts, which deadlocked the client on most launches. The Bushy Pink Petals resource pack was written for Respackopts. Without it, the pack applies all of its model changes and has no option screen.
- **Left out on purpose:**
  - `[Let's Do Addon] Compat`: its recipe mixin crashed datapack loading ("Not a JSON Object: null").
  - Let's Do BloomingNature and Biolith: Biolith's feature sorter and Oh The Biomes We've Gone's FeatureRecycler crashed the first chunk generation ("Index 11 out of bounds for length 11").
- **Pinned versions:** Stack Refill stays on 6.1, because 6.2 needs Collective 8.61 and the pack has 8.57. More Axolotl Variants and its API are pinned to their NeoForge builds, because Modrinth tags the Fabric jars for both loaders.
- Typewriter Day Counter is a datapack, so it lives in `global_packs/required_data/` instead of `mods/`.
- `kubejs/server_scripts/homestead_compat.js` (ported from the Homestead modpack) lets Farm & Charm flour and Create wheat flour be crafted into each other, because each mod's recipes only accept its own.

## What changed from the previous Aero build
- **Removed (22 mods):** Refined Storage and its add-ons, Integrated Dynamics and its add-ons (Integrated Crafting, Terminals, Tunnels, Cyclops Core, Common Capabilities), LaserIO, Super Factory Manager, Retro Factory Manager, Tempad, Simple Teleporters, Tele Pastries, Cable Tiers, Extra Disks, Create Rail Grinding, Resourceful Config, RS Infinity Booster, RS Polymorph and Replication RS2 Bridge. A world that has items or networks from these will lose them.
- **Added (37 mods):** mainly the dungeon and structure mods (Dungeon Crawl, Yung's Better Dungeons and Nether Fortresses, Dungeons and Taverns, Golem Dungeons, Raided), Iron's Spells 'n Spellbooks with Cataclysm Spellbooks and related libraries, Compact Machines, Toms Storage, Cooking for Blockheads, Comforts, Create Central Kitchen, Bountiful, plus performance mods (Noisium, More Culling, Let Me Despawn, Accelerated Decay). `libjf` goes from 3.17.4 to 3.17.7.
- **Quests:** 31 quests repaired, and new Aero Field Guide, Aero Workshop, Bounty Board, Iron's Spells and Traveler's Quest chapters. See `docs/EXPORT-CLEANUP-v1.5-from-friend.md`.
- **Combat update (2026-11-10, pack 1.5.1):** adds Hordium 1.0.3 (random raids) and Enhanced Celestials 2 (Core 2.0.3.4 and Default Lunar Events 2.0.1.4, with CorgiLib and Data Anchor), the `Aero Combat` datapack and resource pack in `global_packs/required_*`, `config/hordium-common.toml`, and six updated quest files. Aero Combat defines five craftable Gate Pearls (Gateways to Eternity) and five Hordium raids that unlock from world day 7 to 20 on moon phases 2 and 6. It also makes blood moons 1.5 times as dangerous and keeps them off those phases. The friend's notes are in `docs/COMBAT-UPDATE-2026-11-10-from-friend.md`. Their Apotheosis final-wave idea is not implemented.

## What was checked
All of this ran on a test server built from these mods with our terrain.

- The server boots with the v1.5 server-side mod set and our terrain mod.
- **Noisium and our terrain:** generated the same area with and without Noisium. Terrain shape and lake water were identical (98.3% of columns matched, and the same 98.2% matched between two runs with no change at all; the rest were tree and boulder placement). Our lake-water mixin still works with it.
- **Speed:** a 64-chunk pre-generation ran at 149 to 159 chunks per second between 32 and 62 chunks out with Noisium, and 148 to 155 without it, so Noisium makes no measurable difference here. The previous build measured 121 to 137 chunks per second at 54 to 116 chunks out.
- **Structure fix on our terrain:** without it, 8 of 10 Better Dungeons small dungeons had little or no ground under them, and the Ancient City (y -52 to -22) and Cataclysm's Ancient Factory (y -31 to 0) sat far below every island. With it, the three small dungeons that generated in a 1.5 km square all had ground under at least 56% of their footprint. The cost is that these structures become much rarer: about 27 times fewer small dungeons per chunk (3 in 9,216 chunks against 10 in 1,133), the nearest Better Dungeons skeleton dungeon moved from 45 to 2,848 blocks from spawn, the nearest Ancient City from 473 to 4,945, and the nearest Ancient Factory from 6.6 km to 34 km. The Amethyst Nest was 21.6 km away without the fix and wasn't found within the search range with it, and the Soul Black Smith wasn't found in either.
- **Repo itself:** every metafile parses, there are no duplicate filenames, the server-side set matches the server that booted, and all 393 Modrinth download links return 200 with the right file size.

- **Combat update on the test server:** boots with no new errors. Gateways registered 17 gateways (12 before, plus the 5 new ones), and Hordium loaded 8 raids (its 3 samples plus the 5 new ones). All 18 modded mobs the new definitions use (Cataclysm, Iron's Spells, Friends & Foes, Raided) summon. `/hordium why aero_combat:sky_raiders` on our terrain passes the sky and dimension checks and waits only for day 14 and the moon phase. Terrain and lake water are unchanged.

### Chunk generation speed on the VPS (1.5.1+sky.2, 2026-11-11)
Measured with `/api/fullgen` in copies of the VPS server folder (8 vCPU, 16 GB), the real server stopped, each copy on a fresh seed-2 world. Each number is one new 33×33-chunk area taken to FULL with non-ticking tickets, after a warm-up area.

| Mod set | Full chunks per second (three areas) |
|---|---|
| Everything, with Terrain Slabs and default Streams | 9.7, 19.7, 14.3 |
| **Without Terrain Slabs, default Streams (what ships)** | 19.1, 20.6, 14.7 |
| Without Terrain Slabs, tuned Streams | 21.4, 23.0, 17.6 |
| Without Terrain Slabs and Streams | 23.1, 23.5, 17.4 |
| Without all the 2026-11-11 biome mods (and Nature's Delight, which needs Nature's Spirit) | 28.4, 36.2, 27.4 |

- Sustained rocket flight at view distance 8 needs roughly 35 new chunks per second.
- During generation the server used about 3 of the 8 cores on average. Its worldgen threads spent most of their time waiting for work, not on locks, so the cost per chunk sets the speed almost directly.
- Profiles (JFR) of the full pack: the biome stage spends extra time in Lithostitched's biome injector and the larger climate search, the surface stage in TerraBlender's surface rules, and the features stage in Terrain Slabs and Streams Reflowing's river carver. In every mod set, about a fifth of the noise stage goes to patches that several mods put on vanilla's structure-blending code (`Beardifier`), and cave features (dripstone, glow lichen, moss) and ores spend time searching the empty space between islands.
- The VPS showed 11 to 20% CPU steal during these runs, so expect the numbers to vary.

### Chunk generation shortcuts in the terrain mod (2026-11-11)
`skyislands` takes four shortcuts in sky-island worlds (class `skyislands.FastGen`). Start the server with `-Dskyislands.fastgen=false` to turn them all off.
1. **No structure blending where there is nothing to blend.** When none of the Beardifier's fields hold anything (vanilla's lists, YUNG's API and Moog's Structures lists, a RoadWeaver plan with an empty density stamp), the chunk gets vanilla's zero blending density instead. Unknown data keeps blending on and is logged once.
2. **All-air cells are skipped.** In those chunks, a 4x4x4 cell whose interpolation corners are all below zero, with no lake water, is left as air without working out its 64 blocks (about 92% of cells).
3. **Cheaper lake lookups.** Each chunk first checks whether any lake island can reach it, and island lookups go through a per-thread cache.
4. **No decoration in empty sky.** Biomes that exist only more than 16 blocks from any land don't get their features tried, and an ore blob whose surroundings are all air isn't placed block by block (it still draws the same random numbers).
5. **No biome lookups high in the sky.** Above the highest point any nearby island can reach (from each island's parameters at their noise maxima, plus 40 blocks), and never below y=240, a column's biome samples reuse the first one: depth only falls with height and the other climate parameters don't change, so the multi-noise result is the same. On the VPS this skipped about a quarter of all biome lookups (Lithostitched's biome injection is most of their cost).
6. **Excessive Utilities' red orchid** reads every block from y=-64 to 16 of its chunk looking for redstone ore; it is skipped where that band is all air, since it then finds nothing and draws no random numbers.

Checked on the VPS: 432 chunks through the world's generator with the shortcuts on and off differ in no block and in none of their 995,328 biome samples (including lakes and chunks with roads or structures kept blending). Full chunks from a world with the shortcuts differ from a world without them in 18.9% of block columns, the same as two worlds without them differ from each other (19.1%, decoration that depends on chunk generation order). Speed in the same three areas went from 21.4, 22.9, 17.0 (and 23.8, 24.6, 17.1 in a second run) to 28.5, 38.3, 16.8 full chunks per second.

Rerun both checks (`/api/fastcheck` and `/api/colhash` in the worldgen lab) after adding or updating mods that touch world generation.

### Structure and oil density in sky-island worlds (2026-11-11)
**Off by default since 2026-11-11 (1x).** Most of the sky is empty, so most structure placements land on nothing. With `-Dskyislands.structureDensity=2`, `skyislands.StructureDensity` packs every overworld structure set (random-spread placement) twice as densely: spacing and separation divided by the square root of 2, from the values all datapacks leave (so Beyond Mines' own spacings are the starting point). Sets that can also generate in the Nether or the End, and strongholds, are left alone. `/locate` and explorer maps search the same grid. It only affects land generated afterwards. When it was on at 2x, the VPS packed 143 of 144 sets closer (for example Better Dungeons' small dungeons 10/6 to 7/4 chunks, Cataclysm's Ancient Factory 112/70 to 79/49).

The Aero Sky Islands datapack also doubles oil: Oritech oil springs 1 in 350 chunks to 1 in 175 (1 in 120 to 1 in 60 in desert-pyramid biomes), PneumaticCraft oil lakes 1 in 25 to 1 in 12 on the surface and 1 in 6 to 1 in 3 underground.

### Structure template upgrades (2026-11-11)
About three quarters of the pack's 8,930 structure templates were saved by older Minecraft versions, and upgrading one with DataFixerUpper happens the first time it is used, during chunk generation. A teleport into fresh land froze the VPS for 35 seconds, about 90% of it upgrading templates. `skyislands` now keeps a disk cache of upgraded templates (`skyislands-cache/structures-<data version>-<mod list hash>/`, 84 MB), filled by a low-priority background thread after the server starts (187 s on the first boot with a new mod list; later boots only check hashes). The same thread first builds DataFixerUpper's upgrade rules for all 47 old data versions. On each boot it re-upgrades 25 cached entries and compares them; on the first check, none differed. Loading one fresh far chunk took 12.2 s with only the rules warmed and 1.9 to 5.0 s with the cache. Turn them off with `-Dskyislands.dfuWarmup=false` and `-Dskyislands.structureCache=false`; delete the folder to rebuild the cache.

### Tried and dropped
- **ScalableLux** (0.3.0-alpha.0.8, with Sable's incompatibility overridden and the one-line sub-level light fix from Sable issue #1638 applied in `skyislands`): lighting left the worldgen threads, but full generation got slower in the same areas (14.2 against 17.7 chunks per second, warm-up area 7.7 against 20). Not shipped. The Sable fix stays in `skyislands` as an optional mixin; it changes nothing without ScalableLux.
- **C2ME and Moonrise:** Sable's tracker has open incompatibility issues for both (#634, #727, #318).

### Speed on the VPS after these changes (2026-11-11)
Same three areas, full chunks per second: 34.1, 45.2, 22.3 (no shortcuts: 21.4, 22.9, 17.0).

### Not checked yet
- Joining the VPS on 1.5.1+sky.2 with a client, including whether Advanced Loot Info now gets its data (DirectAuth, which held it back, is gone).
- Starting a Hordium raid or a Gate Pearl needs a player in game, so whether raiders find somewhere to stand on small or steep islands is untested.
- A full install through the packwiz installer. The 119 CurseForge entries can only be resolved by the installer at install time, so any mod whose author blocks third-party downloads would show up then as a manual download.
- The Prism instance zip has not been imported and launched.
