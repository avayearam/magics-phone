#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -encoding UTF-8 -d "$out" \
  src/site/magics/phone/bridge/Pcm16.java \
  src/site/magics/phone/bridge/AudioFrame.java \
  src/site/magics/phone/bridge/CallSessionGate.java \
  tests/site/magics/phone/bridge/BridgeLabTest.java
java -cp "$out" site.magics.phone.bridge.BridgeLabTest
