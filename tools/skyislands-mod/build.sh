#!/bin/bash
# Builds skyislands-1.0.0.jar against a NeoForge 21.1.262 server's libraries folder.
# usage: ./build.sh [path to the server's libraries folder]   (default: /opt/minecraft-servers/exosphere-aero/libraries)
set -e
L=${1:-/opt/minecraft-servers/exosphere-aero/libraries}
cd "$(dirname "$0")"
CP=$(find "$L" -name '*.jar' | grep -vE 'net/neoforged/neoforge/21|net/minecraft/server/1.21.1/server-1.21.1.jar|-unpacked|-slim|-mappings|guava-20.0|gson-2.8.9|asm[a-z-]*-9\.3|commons-lang3-3.8.1' | tr '\n' ':'):$L/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar
mkdir -p build/classes
javac --release 21 -nowarn -proc:none -cp "$CP" -d build/classes $(find src -name '*.java')
(cd build/classes && jar cf ../../skyislands-1.0.0.jar .)
jar uf skyislands-1.0.0.jar -C res .
echo "built $(pwd)/skyislands-1.0.0.jar"
