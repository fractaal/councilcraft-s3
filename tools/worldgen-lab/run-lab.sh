#!/bin/bash
# Starts a NeoForge server folder (with the pack's server mods and skyislands) with the worldgen lab, then serves the
# viewer at http://127.0.0.1:8765/. Commands can be sent by appending lines to <server>/console.in, e.g. "stop".
# usage: run-lab.sh <server folder> [settings file, default tools/worldgen/settings-islands.json]
set -e
S=$(cd "$1" && pwd)
SETTINGS=$(realpath "${2:-$(dirname "$0")/../worldgen/settings-islands.json}")
# The noise settings file the server's world uses, so Verify compares like with like. LAB_TEMPLATE overrides it.
TEMPLATE=${LAB_TEMPLATE:-$(cd "$(dirname "$0")/../.." && pwd)/global_packs/optional_data/Aero Sky Islands/data/minecraft/worldgen/noise_settings/overworld.json}
D=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$D/../.." && pwd)
JAVA=${JAVA:-/usr/lib/jvm/java-21-openjdk/bin/java}
if pgrep -f "java.*worldgenlab.settings=" >/dev/null; then
  echo "A worldgen lab is already running; stop it first with $(dirname "$0")/stop-lab.sh" >&2
  exit 1
fi
cp "$D/worldgenlab.jar" "$S/mods/worldgenlab.jar"
cd "$S"
# A lab runs next to the live server, so it can't take the live server's Simple Voice Chat port: failing to bind it ends
# the lab's JVM with status 1 a few seconds after the server finishes loading.
VC=config/voicechat/voicechat-server.properties
[ -f "$VC" ] && sed -i 's/^port=24454$/port=24464/' "$VC"
rm -f console.in; touch console.in
tail -f --pid=$$ console.in | "$JAVA" @user_jvm_args.txt \
  -Dworldgenlab.settings="$SETTINGS" \
  -Dworldgenlab.template="$TEMPLATE" \
  -Dworldgenlab.web="$D/web" \
  @libraries/net/neoforged/neoforge/21.1.262/unix_args.txt nogui > server.out 2>&1
