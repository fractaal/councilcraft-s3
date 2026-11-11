#!/bin/bash
# Builds worldgenlab.jar against a NeoForge 21.1.262 server's libraries folder and the skyislands jar.
# usage: ./build.sh [libraries folder] [skyislands jar]
set -e
L=${1:-/opt/minecraft-servers/exosphere-aero/libraries}
cd "$(dirname "$0")"
SKY=${2:-../skyislands-mod/skyislands-1.0.0.jar}
CP=$(find "$L" -name '*.jar' | grep -vE 'net/neoforged/neoforge/21|net/minecraft/server/1.21.1/server-1.21.1.jar|-unpacked|-slim|-mappings|guava-20.0|gson-2.8.9|asm[a-z-]*-9\.3|commons-lang3-3.8.1' | tr '\n' ':'):$L/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar:$SKY
rm -rf build && mkdir -p build/classes
javac --release 21 -nowarn -proc:none -cp "$CP" -d build/classes $(find src -name '*.java')
(cd build/classes && jar cf ../../worldgenlab.jar .)
jar uf worldgenlab.jar -C res .
echo "built $(pwd)/worldgenlab.jar"
