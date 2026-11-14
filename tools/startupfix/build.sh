#!/usr/bin/env bash
# Builds startupfix.jar, a Java agent with three startup fixes for the client (see src/startupfix/Agent.java).
# Used either by putting the jar in the mods folder (it attaches itself, see src/startupfix/boot/Bootstrap.java)
# or with:  -javaagent:startupfix/startupfix.jar
# The patched JDK class (java.lang.module.Resolver, GPLv2 with the Classpath exception) is built from the
# JDK 21 source by patch.py; its source is kept in resolver-src/ next to the jar. The source file is identical
# in every Java 21 release from 21+35 to 21.0.11.
#   ./build.sh verify   also builds resolver-verify/ : a Resolver that runs both loops and prints whether they agree
set -euo pipefail
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}
LIBS=${PRISM_LIBRARIES:-$HOME/.local/share/PrismLauncher/libraries}
ASM=$LIBS/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar
TAG=jdk-21.0.11-ga
mkdir -p orig/java/lang/module src/java/lang/module
[ -f orig/java/lang/module/Resolver.java ] || curl -sS -f -o orig/java/lang/module/Resolver.java \
  "https://raw.githubusercontent.com/openjdk/jdk21u/$TAG/src/java.base/share/classes/java/lang/module/Resolver.java"
rm -rf build && mkdir -p build/resolver build/agent/startupfix
python3 patch.py plain
"$JAVA_HOME/bin/javac" --patch-module java.base=src -d build/resolver src/java/lang/module/Resolver.java 2> >(grep -v '^Note' >&2)
rm -rf resolver-src && mkdir resolver-src && cp src/java/lang/module/Resolver.java resolver-src/
cp build/resolver/java/lang/module/Resolver.class build/agent/startupfix/Resolver.bin
if [ "${1:-}" = verify ]; then
  python3 patch.py verify && rm -rf resolver-verify && mkdir resolver-verify
  "$JAVA_HOME/bin/javac" --patch-module java.base=src -d resolver-verify src/java/lang/module/Resolver.java 2> >(grep -v '^Note' >&2)
fi
rm -rf src/java
FML=$(ls "$LIBS"/net/neoforged/fancymodloader/loader/*/loader-*.jar | tail -1)
"$JAVA_HOME/bin/javac" --release 21 -cp "$ASM:$FML" -d build/agent src/startupfix/*.java src/startupfix/boot/*.java
mkdir -p build/agent/META-INF/services
echo startupfix.boot.Bootstrap > build/agent/META-INF/services/net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper
printf 'Premain-Class: startupfix.Agent\nAgent-Class: startupfix.Agent\nCan-Retransform-Classes: true\n' > build/manifest.txt
"$JAVA_HOME/bin/jar" --create --file startupfix.jar --manifest build/manifest.txt -C build/agent .
rm -rf build
echo "built $(pwd)/startupfix.jar"
