#!/usr/bin/env bash
# Deterministic Android build. No APK is reported unless Gradle tests and assemble succeed.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/../voice-sip-agent"
OUT="$HERE/dist"
for tool in java git bash sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing build tool: $tool" >&2; exit 2; }
done
if [ ! -e "$APP/.git" ]; then
  echo "Initialize submodule: git submodule update --init --recursive" >&2
  exit 2
fi
if ! env | grep -Eq '^(ANDROID_HOME|ANDROID_SDK_ROOT)=/'; then
  echo "Android SDK path missing. Set ANDROID_HOME or ANDROID_SDK_ROOT." >&2
  exit 2
fi
bash "$HERE/apply.sh"
if [ ! -f "$APP/gradlew" ] || [ ! -f "$APP/sip-pjsua2/native/build-pjsua2.sh" ]; then
  echo "Pinned source missing Gradle or PJSUA2 build helper." >&2
  exit 2
fi
(
  cd "$APP/sip-pjsua2/native"
  bash ./build-pjsua2.sh
)
(
  cd "$APP"
  bash ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
)
APK="$APP/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -s "$APK" ]; then
  echo "Build output missing. No APK produced." >&2
  exit 3
fi
mkdir -p "$OUT"
cp "$APK" "$OUT/Magic-Phone-0.5.4-debug.apk"
sha256sum "$OUT/Magic-Phone-0.5.4-debug.apk" > "$OUT/Magic-Phone-0.5.4-debug.apk.sha256"
echo "Built: $OUT/Magic-Phone-0.5.4-debug.apk"
