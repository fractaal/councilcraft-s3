#!/bin/bash
# Builds memtweaks-1.0.0.jar against the Prism Launcher install's NeoForge 21.1.262 client libraries.
# The Voxy hook targets Voxy by class name, so Voxy's jar isn't needed to compile.
set -e
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/classes build/lib
# MixinExtras ships inside NeoForge's jar; pull it out to compile against.
NEOFORGE=$HOME/.local/share/PrismLauncher/libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar
unzip -q -j -o "$NEOFORGE" 'META-INF/jarjar/mixinextras-neoforge-*.jar' -d build/lib
javac --release 21 -nowarn -proc:none -cp "$(python3 -I classpath.py):$(ls build/lib/*.jar)" -d build/classes $(find src -name '*.java')
(cd build/classes && jar cf ../../memtweaks-1.0.0.jar .)
jar uf memtweaks-1.0.0.jar -C res .
echo "built $(pwd)/memtweaks-1.0.0.jar"
