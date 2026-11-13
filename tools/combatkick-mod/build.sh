#!/bin/bash
# Builds combatkick-1.0.0.jar against the Prism Launcher install's NeoForge 21.1.262 client libraries
# and the Better Combat and Heavier Weapons jars in the given mods folder.
# usage: ./build.sh [mods folder containing bettercombat-neoforge-*.jar]
set -e
MODS=${1:-$HOME/.local/share/PrismLauncher/instances/exosphere-aero-voxy/minecraft/mods}
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/classes
CP=$(python3 -I classpath.py):$(ls "$MODS"/bettercombat-neoforge-*.jar | head -1):$(ls "$MODS"/heavier_weapons-*.jar | head -1)
javac --release 21 -nowarn -proc:none -cp "$CP" -d build/classes $(find src -name '*.java')
(cd build/classes && jar cf ../../combatkick-1.0.0.jar .)
jar uf combatkick-1.0.0.jar -C res .
echo "built $(pwd)/combatkick-1.0.0.jar"
