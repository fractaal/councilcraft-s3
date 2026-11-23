#!/bin/bash
# Builds skyislands-1.0.0.jar against a NeoForge 21.1.262 server's libraries folder.
# usage: ./build.sh [path to the server's libraries folder]   (default: /opt/minecraft-servers/exosphere-aero/libraries)
# Mob Loot Bags (for LuckBags), Sable (for the coaster mixin), Building Gadgets 2 (for GadgetPick) and Sophisticated Core (for the crafting upgrade
# mixins) are compiled against from the mods folder next to that libraries folder.
set -e
L=${1:-/opt/minecraft-servers/exosphere-aero/libraries}
cd "$(dirname "$0")"
NF=$L/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar
# Start from no classes, so a source file that was deleted doesn't leave its class in the jar.
rm -rf build/classes
mkdir -p build/classes build/libs
# MixinExtras (@Local) ships inside NeoForge's own jar.
unzip -o -q -j "$NF" 'META-INF/jarjar/mixinextras-neoforge-*.jar' -d build/libs
CP=$(find "$L" -name '*.jar' | grep -vE 'net/neoforged/neoforge/21|net/minecraft/server/1.21.1/server-1.21.1.jar|-unpacked|-slim|-mappings|guava-20.0|gson-2.8.9|asm[a-z-]*-9\.3|commons-lang3-3.8.1' | tr '\n' ':'):$NF:$(ls build/libs/mixinextras-neoforge-*.jar | head -1)
MLB=$(ls "$(dirname "$L")"/mods/mob_loot_bags-neoforge-*.jar | head -1)
[ -f "$MLB" ] || { echo "no Mob Loot Bags jar in $(dirname "$L")/mods" >&2; exit 1; }
CP=$CP:$MLB
# Sable (for the coaster mixin's ServerSubLevel) is compiled against the same way.
SABLE=$(ls "$(dirname "$L")"/mods/sable-neoforge-*.jar | head -1)
[ -f "$SABLE" ] || { echo "no Sable jar in $(dirname "$L")/mods" >&2; exit 1; }
CP=$CP:$SABLE
# Building Gadgets 2 (for GadgetPick and the gadget sneak mixin) is compiled against the same way.
BG2=$(ls "$(dirname "$L")"/mods/buildinggadgets2-*.jar | head -1)
[ -f "$BG2" ] || { echo "no Building Gadgets 2 jar in $(dirname "$L")/mods" >&2; exit 1; }
CP=$CP:$BG2
# Sophisticated Core (for the crafting upgrade mixins) is compiled against the same way.
SOPH=$(ls "$(dirname "$L")"/mods/sophisticatedcore-*.jar | head -1)
[ -f "$SOPH" ] || { echo "no Sophisticated Core jar in $(dirname "$L")/mods" >&2; exit 1; }
CP=$CP:$SOPH
javac --release 21 -nowarn -proc:none -cp "$CP" -d build/classes $(find src -name '*.java')
(cd build/classes && jar cf ../../skyislands-1.0.0.jar .)
jar uf skyislands-1.0.0.jar -C res .
echo "built $(pwd)/skyislands-1.0.0.jar"
