#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
kotlinc src/site/magics/phone/live/LivePcmBridge.kt tests/site/magics/phone/live/LivePcmBridgeTest.kt -include-runtime -d "$out/test.jar"
java -jar "$out/test.jar"
