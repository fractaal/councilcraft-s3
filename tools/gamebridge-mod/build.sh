#!/bin/bash
# Builds gamebridge-<version>.jar against a NeoForge 21.1.262 server's libraries folder.
# usage: ./build.sh [path to the server's libraries folder]   (default: /opt/minecraft-servers/exosphere-aero/libraries)
# The jar is for the server's mods folder only. It is not part of the packwiz pack and must never go to clients.
set -e
V=0.2.1   # also in res/META-INF/neoforge.mods.toml and Bridge.VERSION
L=${1:-/opt/minecraft-servers/exosphere-aero/libraries}
cd "$(dirname "$0")"
NF=$L/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar
rm -rf build/classes
mkdir -p build/classes
CP=$(find "$L" -name '*.jar' | grep -vE 'net/neoforged/neoforge/21|net/minecraft/server/1.21.1/server-1.21.1.jar|-unpacked|-slim|-mappings|guava-20.0|gson-2.8.9|asm[a-z-]*-9\.3|commons-lang3-3.8.1' | tr '\n' ':'):$NF
javac --release 21 -nowarn -proc:none -cp "$CP" -d build/classes $(find src -name '*.java')
(cd build/classes && jar cf ../../gamebridge-$V.jar .)
jar uf gamebridge-$V.jar -C res .
echo "built $(pwd)/gamebridge-$V.jar"
