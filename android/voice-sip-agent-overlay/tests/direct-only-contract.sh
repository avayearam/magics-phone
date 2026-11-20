#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$HERE/.."
APP="$ROOT/files/app/src/main/kotlin/io/github/sparkcyf/voicesipagent/app"
ENDPOINT="$APP/GeminiLiveEndpoint.kt"
TOKEN="$APP/GeminiTokenStore.kt"
SCREEN="$APP/MagicPhoneScreen.kt"
GRADLE="$ROOT/files/app/build.gradle.kts"
grep -Fq 'const val URL = "wss://voice.magics.site/ws?mode=phone"' "$ENDPOINT"
grep -Fq 'const val ORIGIN = "https://ai.magics.site"' "$ENDPOINT"
grep -Fq 'return listOf(PROTOCOL, "magic-agent.$encoded")' "$ENDPOINT"
grep -Fq 'private fun validToken(value: String?): Boolean = MagicLiveHandshake.isDeviceToken(value)' "$ENDPOINT"
grep -Fq '.url(MagicLiveHandshake.URL)' "$ENDPOINT"
grep -Fq 'builder.header("X-Magic-Phone-Caller", it)' "$ENDPOINT"
grep -Fq 'fun normalizeCallerNumber(value: String?): String?' "$ENDPOINT"
grep -Fq 'MagicPhoneCredentialInput.deviceToken(token)' "$TOKEN"
grep -Fq '0.5.8-direct' "$GRADLE"
grep -Fq 'versionCode = 16' "$GRADLE"
if grep -Eq 'phone[.]magics[.]site|magic-auth[.]|LEGACY_URL|BRIDGE_ACCESS_TOKEN|Cloudflare' "$ENDPOINT"; then
  echo "FAILED: legacy Worker reference in Android endpoint" >&2; exit 1
fi
if grep -Eq 'phone[.]magics[.]site|BRIDGE_ACCESS_TOKEN|Cloudflare|Worker:' "$APP/MainActivity.kt"; then
  echo "FAILED: stale Worker endpoint or credential label in Android UI" >&2; exit 1
fi
grep -Fq 'توکن اختصاصی دستگاه' "$SCREEN"
grep -Fq 'https://ai.magics.site/call/device-api.php' "$ROOT/files/app/src/main/kotlin/io/github/sparkcyf/voicesipagent/app/MagicPhoneApi.kt"
grep -Fq 'کلید API این دستگاه' "$SCREEN"
grep -Fq 'تماس‌گیرندگان' "$SCREEN"
grep -Fq 'MagicPhoneCredentialInput.deviceToken(tokenDraft)' "$SCREEN"
grep -Fq 'MagicPhoneCredentialInput.googleApiKey(apiKey)' "$SCREEN"
grep -Fq 'https://ai.magics.site/manager/call-agents.php' "$SCREEN"
grep -Fq 'versionName = "0.5.8-direct"' "$GRADLE"
grep -Fq 'settingsStore.setGeminiModeRequested(on)' "$APP/GatewayRuntime.kt"
grep -Fq 'settingsStore.geminiModeRequested()' "$APP/GatewayRuntime.kt"
grep -Fq 'settingsStore.setGeminiModeRequested(false)' "$APP/GatewayRuntime.kt"
grep -Fq 'پاسخ‌گویی با هوش مصنوعی خاموش است' "$SCREEN"
echo "PASS: Android direct-only, token pairing and Gemini call-mode restoration contracts"
