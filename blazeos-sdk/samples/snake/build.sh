#!/bin/sh
# Builds app.wasm and the BlazeOS package. Needs wat2wasm (wabt): `npm i wabt` or your distro's wabt.
set -e
cd "$(dirname "$0")"
WAT2WASM=${WAT2WASM:-wat2wasm}
$WAT2WASM snake.wat -o app.wasm
rm -f snake-native.zip && zip -q -X snake-native.zip manifest.json app.wasm
cp snake-native.zip ../../../app/src/main/assets/blazeos/apps/snake-native.zip
