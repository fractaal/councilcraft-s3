# World checks

These read a world's `region/*.mca` files directly, so run `save-all flush` on the server first. Run them with `python3 -I`. Pass `nbt_helpers.py` (in this folder) as the first argument; the scripts borrow its NBT reader.

| Script | What it tells you |
|---|---|
| `structscan.py nbt_helpers.py <world> [out.json]` | Every generated structure with its height range, and how much solid ground is under and over it. Floor solid near 0% means it hangs in the air. |
| `worldcmp.py nbt_helpers.py <worldA> <worldB>` | Compares two worlds with the same seed column by column (ignoring leaves) and reports differences in terrain and water. Set `HMBITS=10` for a 576-high world. |
| `water_check.py nbt_helpers.py <world>` | Counts water columns, their surface levels, and any with a suspiciously deep drop below. Set `HMBITS=10`. |

Only chunks saved as fully generated are read. Distant Horizons' pre-generation leaves most chunks at an earlier stage, so to test an area, load it first, for example with `forceload add <x1> <z1> <x2> <z2>` (up to 256 chunks per command), then `save-all flush`.
