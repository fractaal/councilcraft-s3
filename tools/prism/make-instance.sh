#!/bin/bash
# Builds a Prism Launcher instance zip that installs and updates the pack from a packwiz URL.
# usage: ./make-instance.sh <URL of pack.toml> [label for the zip file name] [output.zip]
#   e.g. ./make-instance.sh https://raw.githubusercontent.com/OWNER/REPO/main/pack.toml 1.5.0+sky.1
# Import the result in Prism with: Add Instance -> Import from zip.
set -euo pipefail
URL=${1:?usage: make-instance.sh <pack.toml URL> [version] [output.zip]}
VER=${2:-dev}
OUT=$(realpath -m "${3:-councilcraft-s3-prism-$VER.zip}")
HERE=$(cd "$(dirname "$0")" && pwd)
BOOTSTRAP_URL=https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/packwiz-installer-bootstrap.jar
BOOTSTRAP_SHA256=a8fbb24dc604278e97f4688e82d3d91a318b98efc08d5dbfcbcbcab6443d116c
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/minecraft"
sed "s|@PACK_URL@|$URL|g; s|@VERSION@|$VER|g" "$HERE/instance.cfg.template" > "$TMP/instance.cfg"
cp "$HERE/mmc-pack.json" "$TMP/mmc-pack.json"
curl -fsSL -o "$TMP/minecraft/packwiz-installer-bootstrap.jar" "$BOOTSTRAP_URL"
echo "$BOOTSTRAP_SHA256  $TMP/minecraft/packwiz-installer-bootstrap.jar" | sha256sum -c - >/dev/null
(cd "$TMP" && zip -qr "$OUT" .)
echo "wrote $OUT"
