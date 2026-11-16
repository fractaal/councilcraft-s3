# packcheck

Does what every player's game will do on its next launch, before the pack is pushed: takes the update, loads all the
mods, joins a server. Run it before a push that adds or changes a mod jar, or that removes one.

It was written on 2026-11-17, when a new build of Fractal's Peripherals had only ever run with three other client mods.
In the full pack it turned out to use up the game's sound channels within seconds of a speaker sound ending, after
which no new sound could start. Nothing shorter than a real client near a speaker for a few minutes showed that.

## What it does

```
tools/packcheck/packcheck.sh prepare     # copy the instance, run the pack installer against this repo's HEAD commit
tools/packcheck/packcheck.sh launch      # start the copy in a headless window and join the lab server as "packcheck"
tools/packcheck/packcheck.sh errors      # the client-log lines worth reading
tools/packcheck/packcheck.sh shot out.png
tools/packcheck/packcheck.sh key Escape  # or F3, F1 ...
tools/packcheck/packcheck.sh stop
```

- **prepare** copies Ben's real, packwiz-managed Prism instance (`exosphere-aero-voxy`, read only; the copy shares its
  data on disk, so it is quick and free), exports this repo's `HEAD` commit, serves it from a plain local file server
  and runs the instance's own pre-launch command against that address. A wrong hash in `index.toml`, a missing file or
  a bad download address fails here, as it would for a player. It prints which jars the update added and removed.
  Uncommitted changes are refused: `HEAD` is what a push publishes.
- **launch** starts the copy with the instance's own JVM settings inside a headless gamescope, with nothing from the
  desktop passed in, and joins the lab server (`/home/benjude/exosphere-aero-lab/lootcheck`, port 25566). The lab
  server has to be running, with the same mod jars as the pack.

The copy lives in `/home/benjude/exosphere-aero-lab/packcheck`. Commands for the player go through the lab's Game
Bridge: `python3 -I tools/gamebridge-mod/cli/game.py --server /home/benjude/exosphere-aero-lab/lootcheck cmd "tp packcheck 17.5 304.5 44.5 180 -8"`.

## What to expect

| | |
|---|---|
| Time to the world | About 200 s. JEI then holds the picture on "Loading terrain" for another 40 s or so |
| First join | A "choose a starter kit" screen opens after a few seconds; `key Escape` closes it |
| Frame rate | About 15 a second with Ben's shader pack on. The pack's Dynamic FPS does not treat the headless window as unfocused (read off F3) |
| Window | The game draws a 764 x 764 picture in the middle of the 1280 x 720 output |
| Error lines | Ben's instance logs about 660 `ERROR` lines in a normal run, 578 of them from JEI. Compare by logger and count, don't expect none. X11 cursor, FTB Teams "local player id" and CapeJS lines come from the headless window and the made-up player |

## Recording what the player hears

```
PACKCHECK_SOUND=/home/benjude/exosphere-aero-lab/packcheck/heard.wav PACKCHECK_JVM=-Dfp.sounddebug=true tools/packcheck/packcheck.sh launch
...
tools/packcheck/packcheck.sh stop                      # the file is complete once the game has stopped
tools/packcheck/heard.py heard.wav <time of "Sound engine started" in logs/latest.log> [time a game sound was played]
```

OpenAL's "wave" driver writes the game's mixed output to the file; no sound device is opened. `heard.py` knows the
lab's speaker test (a steady 330 + 495 Hz tone with a 1500 Hz tone over it every 8 s) and lists each burst with its
clock time.

Simple Voice Chat opens a sound device of its own on joining a server. With the wave driver both devices write the one
configured file, and the later one writes silence over the game's audio from the top, so only the last half-minute
survives. The script therefore configures a link, and repoints it to a throwaway file once the game's sound engine has
opened the real one. The wall rig's `RIG_SOUND` has no such guard; it needs one if voice chat is ever added there.

To count the game's sound channels in use: `jcmd <game pid> GC.class_histogram | grep 'ChannelAccess\$ChannelHandle'`.
A dozen is normal; 248 means the pool is full and nothing new can play.

## What it does not cover

Windows and macOS, other graphics cards, a real sound device, slow connections, long sessions, and anything a player
has to do by hand. Ben's instance has his own extra mods (Physics Mod), so the copy is his game, not exactly a
friend's. Keys are pressed with `xkey.py` from the wall rig in the Fractal's Peripherals repo.
