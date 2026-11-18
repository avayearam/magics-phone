#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

kotlinc src/site/magics/phone/live/LivePcmBridge.kt tests/site/magics/phone/live/LivePcmBridgeTest.kt -include-runtime -d "$out/live-pcm.jar"
java -jar "$out/live-pcm.jar"

kotlinc tests/voice-api-stubs/VoiceApiStubs.kt \
  integration/app/GeminiLiveMediaPort.kt \
  tests/GeminiLiveMediaPortTest.kt \
  -include-runtime -d "$out/gemini-media-port.jar"
java -jar "$out/gemini-media-port.jar"
