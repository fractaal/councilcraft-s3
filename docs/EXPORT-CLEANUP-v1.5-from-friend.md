# Export cleanup and quest repairs — 2026-11-09

User authorized removal of disabled/unused files for export. Active mod jars, configurations, datapacks, scripts, optional resource packs, shaders, worlds and personal maps are retained.

## Quest repairs
31 quests improved: 18 Workshop/Field Guide nodes, 12 Traveler's Quest nodes and one Aeronautics propulsion milestone. The original generators produced unsigned hash IDs above FTB Quests' positive signed 64-bit range. The game replaced these IDs and discarded unresolved links while language entries kept the old IDs. Repairs preserve the CURRENT IDs, restore links and place matching text inline plus in split/aggregate English files. Both generators now constrain future IDs. The two small guide chapters now have real possession/advancement objectives and explicitly labeled manual setup tests, not checkmark-only shortcuts. Item tasks do not consume supplies. Bountiful tracks its own advancements. Traveler descriptions no longer promise donor-only structures/trades; single-objective boss tasks are required instead of optional-only. No quest/player progression was reset.

The audit inspected all 35 chapters. Ordinary item/advancement tasks may legitimately derive titles from their target; these are not automatically empty quests. Original Ancient Notes narrative nodes and existing informational checkmarks were preserved. Detailed results: tools/quest-pipeline/quest-cleanup-audit.json. Full pre-repair quest backup OUTSIDE the export instance: ../export-maintenance-backup-2026-11-09/quests.

## Export selection
Exclude these personal/generated folders in Prism's export selection: minecraft/saves, minecraft/journeymap, minecraft/local, minecraft/logs, minecraft/screenshots, minecraft/server-resource-packs. Exclude minecraft/servers.dat, minecraft/servers.dat_old, minecraft/usercache.json, minecraft/usernamecache.json and command history if shown. Preserve them locally. Exclude developer tools and handoff/audit notes when making a player distribution; keep this document if change history should accompany the export. .packignore has been expanded, but inspect Prism's export selection explicitly rather than assuming its behavior.

## Removed files
The table is the exact removal plan; execution status is recorded below. Version metadata and published hashes for downloaded mods remain in tools/mod-downloads/*.json. Removed jars can be obtained again from their recorded source/version. These files were removed for space, not because the enabled replacement is broken.


| Relative path or group | Files | Bytes | Reason |
|---|---:|---:|---|
| minecraft/mods/cabletiers-neoforge-1.21.1-0.6.15.jar.disabled | 1 | 1521914 | Previously disabled mod; excluded from active pack |
| minecraft/mods/Chunky-NeoForge-1.4.23.jar.disabled | 1 | 340572 | Previously disabled mod; excluded from active pack |
| minecraft/mods/commoncapabilities-1.21.1-neoforge-2.11.6.jar.disabled | 1 | 283980 | Previously disabled mod; excluded from active pack |
| minecraft/mods/createrailgrinding-1.2.2.jar.disabled | 1 | 883883 | Previously disabled mod; excluded from active pack |
| minecraft/mods/cyclopscore-1.21.1-neoforge-1.30.0.jar.disabled | 1 | 1163177 | Previously disabled mod; excluded from active pack |
| minecraft/mods/entityculling-neoforge-1.11.3-mc1.21.1.jar.disabled | 1 | 1510075 | Previously disabled mod; excluded from active pack |
| minecraft/mods/ExtraDisks-1.21.1-4.0.15.jar.disabled | 1 | 343628 | Previously disabled mod; excluded from active pack |
| minecraft/mods/integratedcrafting-1.21.1-neoforge-1.8.0.jar.disabled | 1 | 513876 | Previously disabled mod; excluded from active pack |
| minecraft/mods/integrateddynamics-1.21.1-neoforge-1.38.1.jar.disabled | 1 | 4500305 | Previously disabled mod; excluded from active pack |
| minecraft/mods/integratedterminals-1.21.1-neoforge-1.11.1.jar.disabled | 1 | 878291 | Previously disabled mod; excluded from active pack |
| minecraft/mods/integratedtunnels-1.21.1-neoforge-1.13.0.jar.disabled | 1 | 744647 | Previously disabled mod; excluded from active pack |
| minecraft/mods/inventorytotem-1.21.1-3.6.jar.disabled | 1 | 29690 | Previously disabled mod; excluded from active pack |
| minecraft/mods/laserio-1.9.11.jar.disabled | 1 | 1305285 | Previously disabled mod; excluded from active pack |
| minecraft/mods/libjf-3.17.4+forge.jar.disabled | 1 | 730989 | Previously disabled mod; excluded from active pack |
| minecraft/mods/refinedstorage-curios-integration-1.0.0.jar.disabled | 1 | 11177 | Previously disabled mod; excluded from active pack |
| minecraft/mods/refinedstorage-jei-integration-neoforge-1.0.0.jar.disabled | 1 | 58343 | Previously disabled mod; excluded from active pack |
| minecraft/mods/refinedstorage-neoforge-2.0.9.jar.disabled | 1 | 4360871 | Previously disabled mod; excluded from active pack |
| minecraft/mods/replication_rs2_bridge-1.21.1-NeoForge-1.2.7.jar.disabled | 1 | 150539 | Previously disabled mod; excluded from active pack |
| minecraft/mods/resourcefulconfig-neoforge-1.21-3.0.11.jar.disabled | 1 | 341009 | Previously disabled mod; excluded from active pack |
| minecraft/mods/retrofactorymanager-1.21.1-1.0.4.jar.disabled | 1 | 332715 | Previously disabled mod; excluded from active pack |
| minecraft/mods/rsinfinitybooster-neoforge-1.21.1-1.0.0.48.jar.disabled | 1 | 25726 | Previously disabled mod; excluded from active pack |
| minecraft/mods/rspolymorph-neoforge-1.21.1-1.2.2.jar.disabled | 1 | 73682 | Previously disabled mod; excluded from active pack |
| minecraft/mods/SimpleTeleportersReforged-1.21.1-2.2.0.jar.disabled | 1 | 331448 | Previously disabled mod; excluded from active pack |
| minecraft/mods/Super Factory Manager (SFM)-MC1.21.1-4.34.0.jar.disabled | 1 | 1597623 | Previously disabled mod; excluded from active pack |
| minecraft/mods/TelePastries-1.21.1-0.11.2.jar.disabled | 1 | 206860 | Previously disabled mod; excluded from active pack |
| minecraft/mods/tempad-1.21.1-3.0.4-all.jar.disabled | 1 | 1696464 | Previously disabled mod; excluded from active pack |
| tools/mod-downloads/aces_spell_utils-1.2.7.5-1.21.1.jar | 1 | 401499 | Duplicate download; enabled copy retained, hashes/API metadata retained |
| tools/mod-downloads/azurelib-neo-1.21.1-3.2.7.jar | 1 | 1323430 | Duplicate download; enabled copy retained, hashes/API metadata retained |
| tools/mod-downloads/bountiful-neoforge-8.0.0-beta.2.jar | 1 | 721006 | Duplicate download; enabled copy retained, hashes/API metadata retained |
| tools/mod-downloads/cataclysm_spellbooks-1.1.11-1.21.jar | 1 | 725204 | Duplicate download; enabled copy retained, hashes/API metadata retained |
| tools/mod-downloads/comforts-neoforge-9.0.5+1.21.1.jar | 1 | 422658 | Duplicate download; enabled copy retained, hashes/API metadata retained |
| tools/mod-downloads/cookingforblockheads-neoforge-1.21.1-21.1.24.jar | 1 | 1266042 | Duplicate download; enabled copy retained, hashes/API metadata retained |
| tools/mod-downloads/create-central-kitchen-2.6.2b.jar | 1 | 511818 | Duplicate download; enabled copy retained, hashes/API metadata retained |
| tools/mod-downloads/kambrik-neoforge-8.0.0-beta.2.jar | 1 | 436428 | Duplicate download; enabled copy retained, hashes/API metadata retained |
| tools/mod-downloads/libjf-3.17.7+forge.jar | 1 | 681557 | Duplicate download; enabled copy retained, hashes/API metadata retained |
| tools/mod-downloads/trading_floor-3.0.16.jar | 1 | 214343 | Duplicate download; enabled copy retained, hashes/API metadata retained |
| minecraft/resourcepacks/Create_Style_for_Refined_Storage.zip | 1 | 2448281 | Refined Storage removed; matching texture pack unused |
| Generated/backup group | 21 | 26128409 | Generated historical log |
| Generated/backup group | 11 | 2546010 | Obsolete backup or generated diagnostics/cache: backlog-backups |
| Generated/backup group | 4 | 156996 | Obsolete backup or generated diagnostics/cache: minecraft\.mixin.out |
| Generated/backup group | 3 | 268543 | Obsolete backup or generated diagnostics/cache: minecraft\crash-reports |
| Generated/backup group | 50 | 33027 | Obsolete backup or generated diagnostics/cache: minecraft\dynamic-data-pack-cache |
| Generated/backup group | 986 | 756519 | Obsolete backup or generated diagnostics/cache: minecraft\dynamic-resource-pack-cache |
| Generated/backup group | 561 | 9740001 | Obsolete backup or generated diagnostics/cache: minecraft\local\crash_assistant |
| Generated/backup group | 7 | 10597595 | Obsolete backup or generated diagnostics/cache: replaced-jars |

Exact per-file removal manifest: tools/export-cleanup-manifest.json (retained for maintenance).

## Execution result
Deleted 1680 files; freed 83316135 bytes (79.46 MiB). No removal failures.

Static checks passed for signed-safe unique IDs, SNBT roundtrips, repaired guide text parity, dependencies and reward tables. In-game quest display has not been retested after these repairs. Existing completed quest flags were preserved; players may already have completed some changed milestones. Generated caches will rebuild on next launch.


Additional fix: Companions observation namespace corrected to companions:puppet_glove. Final validator has zero namespace warnings.

