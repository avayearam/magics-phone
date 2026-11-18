#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$(cd "$HERE/.." && pwd)"
TARGET="$ANDROID_DIR/voice-sip-agent"
FILES="$HERE/files"
PIN="5105a1f447ffa25ba32d0620dbeef44bbc1705ce"
if [ ! -e "$TARGET/.git" ]; then
  echo "Voice SIP Agent submodule is missing. Run: git submodule update --init --recursive" >&2
  exit 1
fi
ACTUAL="$(git -C "$TARGET" rev-parse HEAD)"
if [ "$ACTUAL" != "$PIN" ]; then
  echo "Unexpected upstream revision: $ACTUAL (expected $PIN)" >&2
  exit 1
fi
while IFS= read -r -d '' src; do
  rel="${src#"$FILES"/}"
  mkdir -p "$TARGET/$(dirname "$rel")"
  cp "$src" "$TARGET/$rel"
done < <(find "$FILES" -type f -print0)
echo "Gemini Live overlay applied to pinned Voice SIP Agent $PIN"
