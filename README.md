# CouncilCraft Season 3: packwiz repo

CouncilCraft season 3 runs BM Exosphere 2 Aero. This is the pack definition, on Minecraft 1.21.1 with NeoForge 21.1.262. It starts from the "BM - Exosphere 2 Aero (Clean) v1.5" build that a friend made, with their 2026-11-10 combat update on top, and adds our sky-island terrain, extra biome mods and a set of cozy mods (farming, cooking, decoration). Pack version `1.5.1+sky.7`.

It holds no mod jars except our own. Every other mod, resource pack and shader is a small `.pw.toml` file that pins an exact version and hash and says where to download it (398 from Modrinth, 119 from CurseForge). Configs, KubeJS scripts, quests and datapacks are real files in the repo.

## What is in the repo

| Path | What it is |
|---|---|
| `pack.toml`, `index.toml` | The packwiz pack and its file index. Rebuilt by `packwiz refresh`. |
| `mods/*.pw.toml` | One file per mod. `side` says where it installs: `both`, or `client` for mods the live server doesn't run (Sodium, Iris and so on). |
| `mods/skyislands-1.0.0.jar` | Our own terrain mod. |
| `mods/combatkick-1.0.0.jar` | Our own client-side combat feel mod (see 1.5.1+sky.4). |
| `mods/memtweaks-1.0.0.jar` | Our own client-side memory and startup mod (see 1.5.1+sky.7 and sky.8). |
| `resourcepacks/`, `shaderpacks/`, `datapacks/` | Metadata for packs hosted on Modrinth, plus the few that exist nowhere else as plain files. |
| `config/`, `kubejs/`, `scripts/`, `defaultconfigs/` | Pack configuration and quests (from v1.5). `config/fml.toml` is left out on purpose, see "Server notes". |
| `global_packs/` | Datapacks and resource packs loaded by the Global Packs mod (see below). |
| `tools/skyislands-mod/` | Source for our terrain mod and a `build.sh`. |
| `tools/combatkick-mod/` | Source for Combat Kick and a `build.sh`, which compiles against a Prism Launcher NeoForge 21.1.262 install. |
| `tools/memtweaks-mod/` | Source for Memory Tweaks and a `build.sh`, built the same way as Combat Kick. |
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
- **Heavier Weapons must be on the server before clients get it.** Its hitstop packet channel is required, so a client with the mod can't join a server without it. The server decides hitstop lengths from its own `config/heavier_weapons.properties`, so copy the pack's version there too.

## What changed in 1.5.1+sky.8 (2026-11-14)
The pack is now called **CouncilCraft Season 3** (`pack.toml`, the game window title, the loading screen's "Finalizing" line, the Prism template). The line under the main menu's buttons reads "CouncilCraft Season 3" in place of the old pack's Discord invite (`config/fancymenu/customization/Beyond Mines.txt`). Quest text, the BM Hub button and the hosting offer on the Multiplayer screen still come from the pack it started from. The rest is client startup time. Nothing changes on the server. Measured with scratch launches of Ben's client, killed at the title screen: 105 s before any of this work, about 62 s with the Memory Tweaks changes below plus the startup agent, and 51.5 s once the agent's class cache is warm.

- **Crash Assistant removed.** It opened a "crashed without a crash report" window whenever the game was closed abruptly, and its messages pointed players at another pack's Discord.
- **Memory Tweaks** (`client`, source in `tools/memtweaks-mod/`) gained startup fixes. Each has a switch in Mods > Memory Tweaks > Config:
  - **Reuse Model Location List** (`reuseModelLocationList`). A list of every block state's model, 2.5 million entries, was built once by ModernFix, once more for each Puzzles Lib mod that uses model events, and again by ModernFix a few seconds later. It is now built once per resource load. About 6 s.
  - **Lazy Model Replacement** (`lazyModelReplaceAll`). Create Thrusters, Fetzi's Asian Deco and Display Delight wrap their models by asking for every model in the game to be replaced, which made ModernFix walk all of them and bake and keep theirs at startup. The wrapping now happens when a model is first baked. About 4.5 s, and those models stay out of memory until needed.
  - **Lazy Model File Entries** (`lazyModelFileEntries`). More Axolotl Variants API loops over the list of model files, and with ModernFix each entry it touched was parsed, so every model file in the game was parsed and thrown away. About 5 s.
  - **Trust EMF Folder Listing** (`trustEmfFolderListing`). Entity Model Features lists its model folders, then still asked every mod pack about each file the listing hadn't found. About 2 s.
  - **Trace log events dropped.** NeoForge built and queued a TRACE line for every event sent to every mod and then discarded them. About 4 s. `-Dmemtweaks.keepTraceLogs=true` turns this off.
  - **Startup Banner** (`startupBanner`). Shows "fractalWARP STARTUP BOOST 9000 ACTIVE" on the first loading screen when the startup agent is loaded, which is now the default. The same switch hides the agent's copy of the banner on the early loading window. After the spinner a clock counts this start against the previous one ("32s / 54s"; red once it runs a quarter past it; "COLD START 32s" while the agent's class cache is being rebuilt, which happens on the first start after any mod change). When the loading screen closes, the menu shows the result in the same spot for about 10 s before it fades: "Started in 54s. Would have taken ~105s.", or "Cold start: took 79s. Your next start should be much faster." Times count from the start of the Java process to the first frame of the menu; the same line goes to the log as `Startup banner: ...`, and a warm start's time is kept in `startupfix/last-start.txt` for the next clock. **The "would have taken" figure is an estimate, not a measurement:** the start's time times 2 with the two JVM flags below, or times 1.75 without them, which are the ratios measured on one machine between the pack before this work (105 s) and after it. It replaced a warm-against-cold comparison, which said little because a cold start already has every fix except the class cache. On the early loading window the clock stands still from about 17 s until the loading screen takes over, because that window stops redrawing once Minecraft owns it.
- **Early loading screen restored.** The pack's settings always asked for Drippy's early loading window (`drippy_early_window`) and carried a finished design for it, but the add-on that provides it was never in the pack, so the game showed no window, then a black one, for the first part of startup. Added **Drippy Early Loading Module** 3.1.5 (`client`), which shows the pack's picture, logo and progress bar from about 2 s after launch, and **Drippy macOS Early Loading Patch** (`client`), because the module draws with OpenGL calls macOS doesn't support. The patch is built for module 3.1.2; 3.1.5's classes are identical, and on Linux it loads and stays inactive, but it hasn't been run on a Mac. Known limit: the module ignores window resizes until Minecraft's main thread starts polling events (about 14 s in), so on a tiling window manager the picture would sit in a corner on a red background until then. The startup agent (below) fixes that.
- **Logging config:** Log Begone's two phrases moved to Async Logger's substring filter (`config/asynclogger.toml`, `config/logbegone.json`). Log Begone compiled a regular expression for every log line.
- **Startup agent** (`mods/startupfix-1.0.0.jar`, source in `tools/startupfix/`). Fixes for things mods cannot reach: the JDK 21 module resolver's cubic loop (about 19 s under G1, 65 s under ZGC), Mixin's opcode-name lookup, Roxy's conversion of Voxy's jar on every launch, a disk cache of transformed classes (about 5 to 8 s; the first launch after any mod change records it, so that one is slower), and the early loading window following resizes from its first frame. It also draws the "fractalWARP" banner on the early loading window.
  - **On by default, no Java arguments.** NeoForge runs the jar's early hook in the first half second of a client launch; the hook starts a short-lived helper Java process that attaches to the game and loads the same jar as a Java agent (about 0.2 s). Measured the same as loading it with `-javaagent`. On a server the hook does nothing.
  - **Opting out:** `enabled=false` in `config/startupfix.properties` (written on the first launch), or name parts in its `off=` line: `resolver, opcodes, roxy, transformcache, earlywindow, earlybanner`.
  - **If attaching fails** (a Java started with `-XX:+DisableAttachMechanism`, or a runtime without the `jdk.attach` module) the game starts as it would without the agent and prints `[startupfix] not active: ...`. Java prints a warning that an agent was loaded dynamically; that is expected.
  - **Two JVM flags are worth about 7 s more** and cannot be set from inside the game: `-XX:SymbolTableSize=4194304 -XX:MetaspaceSize=1g` (after `-XX:+UnlockExperimentalVMOptions`). The Prism template now has them, so new imports get them; an existing instance needs them added by hand in Settings > Java > Java arguments. Scratch launches to the title screen on one machine: about 52 to 54 s with the flags, about 59 to 61 s without, against 105 s before any of this.
  - **Not tested:** Windows and macOS (the attach step and the helper process are plain JDK features, but only Linux was run), and Prism's downloaded Java on those systems. On macOS the early-window parts are skipped.

## What changed in 1.5.1+sky.7 (2026-11-14)
Client memory and startup time. Nothing changes on the server. Measured on Ben's client from heap dumps and launch logs: about 2.4 GB less live heap (8.6 GB to 6.25 GB under ZGC; an estimated 6.7 GB to 4.8 GB under the G1 settings players get) and about 48 seconds less from launch to title screen.

- **Memory Tweaks** (`client`, our own mod, source in `tools/memtweaks-mod/`). Settings are in Mods > Memory Tweaks > Config, which edits `config/memtweaks-client.toml`; the pack doesn't ship that file, so a player's own values survive pack updates. Each part logs what it did under `memtweaks` in `logs/latest.log`, and each can be turned off:
  - **One in-memory copy of each bundled jar** (`shareNestedJars`). NeoForge opens every jar that is bundled inside another mod's jar up to three times, and the JDK reads each one fully into memory. This points the duplicates at one copy: 351 MB freed.
  - **Voxy section cache size** (`voxySectionCacheSize`, default 1024). Voxy hard-codes 2048 recently used world sections at about 262 KB each and never shrinks the cache. 1024 saves about 270 MB; raise it if you have memory to spare.
  - **Entity textures out of the block atlas** (`entityTexturesOutOfBlockAtlas`). The Grappling Hook mod's atlas file adds the whole `entity` texture folder to the block atlas, and a folder entry applies to every mod, so 42 of the atlas's 99 megapixels were other mods' entity textures. This limits that one entry to the Grappling Hook mod's own two textures. The atlas goes from 16384x8192 to 8192x8192, which also halves Voxy's in-memory copy of it and the video memory the atlas takes. If something that used to render shows the purple and black missing texture, turn this off and press F3+T.
  - **Shared model faces** (`shareIdenticalQuads`). 74% of the 5.8 million baked model faces were exact copies of another, because models for different states of a block repeat the same faces. This keeps one of each: about 300 MB. If blocks render with wrong or missing faces, turn this off.
- **MoreCulling removed.** On every resource load it asked for the baked model of every non-solid block state on the main thread: 41 seconds of each launch, and it defeated ModernFix's build-on-first-use for models and block shape caches. Without it, live heap is 1.15 GB lower. What it gave in return was extra hidden-face culling on non-solid blocks, leaves and item frames; Ben saw no frame rate difference without it.

## What changed in 1.5.1+sky.6 (2026-11-14)
Combat Kick (`client`) and the grappling hook.

- **Settings in game:** Mods > Combat Kick > Config edits `config/combatkick-client.toml`. The pack no longer ships that file, so NeoForge writes it with Combat Kick's defaults (which now include the pack's `strength = 0.5`) and a player's own edits survive pack updates.
- **Heavier Weapons' screen shake** has its own slider there, "Heavier Weapons Screen Shake" (`heavierWeaponsShake`, 0 turns it off). It multiplies the shake Heavier Weapons already applies (1.5x in its config), and like Combat Kick's own kick it also follows the vanilla Screen Effect Scale slider.
- **Punchier grappling hook** (`config/grapplemod-common.json` and `grapplemod-properties.json`, server and clients): climbing the rope with sneak+W/S is twice as fast (`climbSpeed` 0.3 to 0.6 blocks per tick), W/A/S/D builds swing momentum 1.5 times as fast (`playerMovementMultiplier` 1.0 to 1.5, the mod's cap is 2), and the hook flies at 4.5 blocks per tick instead of 2.9 (a 64-block throw in about 0.7 s instead of 1.1 s; the cap is 5). Hooks customised at the modifier block keep their own values.
- **Kill insignia back to 1x** of the GUI scale, from 1.25x: the diamond, the portraits and the `xN` counter. The hearts line and the combat report stay at normal text size, as before.

## What changed in 1.5.1+sky.5 (2026-11-13)
Combat Kick (`client`, source in `tools/combatkick-mod/`) now rewards kills, tuned by Ben in singleplayer. Settings are in `config/combatkick-client.toml`; NeoForge reloads it while the game runs. Nothing changes on the server.

- **Kill insignia** above the hotbar when you kill a hostile mob: a diamond with the mob's portrait crossed out, and the other mob types from the streak orbiting it. It grows more elaborate and hotter in colour (white, yellow, orange, red) as the streak builds. Each kill fires a lens-flare starburst and a diamond pulse, both bigger with the streak (capped at x40), and an XP ding that rises in pitch, with an echo.
- **Streak counter** (`xN`) that jumps in size on each kill and eases back, and a running count of the hearts of damage you've dealt under it. Damage counts only your own hits: each drop in a mob's health is matched to the hits the server reported just before it.
- **Streak window**: 5 s after one kill, growing to 15 s at x20. The insignia stays up for the whole window and fades in its last second; then a combat report fades in, e.g. `x47 KILL STREAK | TOTAL DAMAGE 147.5` and a heart. The timers stop while singleplayer is paused.
- **Ragdoll hits**, only with Physics Mod ragdolls installed (not part of the pack): the killing swing drives the body part it struck, the way the swing was going, and the rest of the body follows through its joints. Without Physics Mod these hooks are skipped.

## What changed in 1.5.1+sky.4 (2026-11-13)
Combat feel, tuned by Ben in singleplayer. Measured over 90 s of fighting with Java Flight Recorder, the three mods below plus Physics Mod took about 2% of the render thread, less than Better Combat itself.

- **Better Combat – Heavier Weapons** (`both`): hitstop. On a hit the swing animation and the struck mob freeze for a few ticks, the mob's knockback is held until the freeze ends, and the camera jitters. Lengths per weapon category in `config/heavier_weapons.properties`, about 1.5 times the mod's defaults (sword 4 ticks, claymore 9, hammer and greathammer 10, crits +4), plus Simply Swords' greataxe, greathammer, warglaive and chakram, which the mod doesn't list. Its sound is on, camera shake 1.5×, mob shake 3×.
- **BloodyParticles!** (`client`): per-mob blood on hit. 3× particles, 4× on crits, the bloodier low-health overlay.
- **Combat Kick** (ours, `client`, source in `tools/combatkick-mod/`):
  - Each Better Combat hit kicks the camera in the direction of the swing (read from the attack animation's name), and a damped spring sways it back. Slower weapons, combo finishers, crits and multi-target hits kick harder, and kicks stack to a cap. It moves only the view, never your aim.
  - Heavier Weapons zeroes WASD input during hitstop, which also cancels sprinting. Combat Kick puts the input back, so you keep moving and sprinting through the freeze.
  - Tuned in `config/combatkick-client.toml` (shipped at `strength = 0.5`; from sky.6 that's the built-in default and the file isn't shipped); NeoForge reloads it while the game runs.
- **KubeJS web server off** (`kubejs/config/web_server.json`): its accept loop kept a whole CPU core busy on Ben's client.
- Physics Mod ragdolls were tried alongside and are Ben's own client addition, not part of the pack.

## What changed in 1.5.1+sky.3 (2026-11-12)
All of it is in the terrain mod and the Aero Sky Islands datapack, and it needs a new world.

- **Rivers pick their own course.** A river now searches for a route through low ground from open water near its lake's middle, is smoothed into curves, meanders, changes width, and cuts through every island it crosses. Banks have uneven slopes. Structures are not placed within 20 blocks of a river.
- **Lake chains** (the `chains` object in the archipelago settings): a lake can drain into a lower neighbour's lake instead of off the edge, across a bridge of land where there is open sky between the islands, gently or as a waterfall off a ledge.
- **Land bridges** (the `landBridges` object): natural stone arches between neighbouring main islands, wide enough to ride a horse across. Each island bridges to its best neighbour and sometimes a second, which makes chains and branches, not a mesh. A bridge is broad and deep where it leaves each island and slender in the middle.
- **Structures over open sky** (`StructureFootings` in the mod):
  - A surface structure that used to land on the bottom of the world is planned at a made-up ground height, about the height of the islands in that part of the sky, and gets an island of its own under it. In a 784-block lab area this took the structure count from 66 to 71 or 72 and cost no measurable chunk speed.
  - An underground structure floating in the open gets a mass of rock under it.
  - Support pillars with no land to stand on are cut. They used to run to the bottom of the world.
  - `-Dskyislands.structureIslands=false` turns off the made-up ground and the islands, `-Dskyislands.structureFootings=false` all of it.
- **Fixes:** river water no longer hangs in the air off an island's edge where the game blends a bank away, and a land bridge no longer loses 64-block stretches (bridge tiles shared cache keys).
- **Grappling hook** (config): keeps your momentum after you let go, and its horizontal speed cap is doubled to 8 blocks per tick.

Not checked: none of the 1.5.1+sky.3 terrain has been timed on the VPS yet; the lab numbers are from a desktop.

## What changed in 1.5.1+sky.2
- **Terrain:** the archipelago settings replace the lakes settings. Small islets are 1.5 times as dense as in the first archipelago test, so the median gap to the nearest islet is 20 blocks. A bug that clipped about a fifth of the islet land is fixed.
- **Biome mods:** TerraBlender, Oh The Biomes We've Gone (with Oh The Trees You'll Grow), Nature's Spirit, Geophilic and RoadWeaver. Countered's Terrain Slabs was tried and dropped: it cost about a fifth of chunk generation time (see the speed table).
- **Rivers and banks are ours; Streams Reflowing is gone (2026-11-12).** On our islands Streams carved almost no streams: in a 656-block lab area, its carving on against off differed by about 900 water columns of puddles and no flowing water, and no setting changed that (any biome as a mouth, joining dead ends, a real land-height estimate, sea level at y 150, planning first, 3x wetness). Instead the terrain mod gives each lake island an outflow river (the `rivers` object in the archipelago settings): a channel from the lake across its low side to the island's edge, cutting a gorge where the land is higher, with the water a block lower every 3 to 6 blocks so it runs as flowing water once the chunk ticks and pours off the edge as a waterfall. The mod's `banks` and `bank_plants` features (added to overworld biomes by the datapack's biome modifiers) dress every water edge in rings of biome materials (bed, waterline, bank, outer ring) and add grass, ferns, sugar cane, seagrass and lily pads. In the lab, 64 chunks around a river matched with the generation shortcuts on and off, and dropping Streams gives back its 10-17% of chunk generation time.
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
| Without Terrain Slabs, default Streams | 19.1, 20.6, 14.7 |
| Without Terrain Slabs, tuned Streams | 21.4, 23.0, 17.6 |
| **Without Terrain Slabs and Streams (what ships)** | 23.1, 23.5, 17.4 |
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
