#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$HERE/.."
APP="$ROOT/files/app/src/main/kotlin/io/github/sparkcyf/voicesipagent/app"
ENDPOINT="$APP/GeminiLiveEndpoint.kt"
TOKEN="$APP/GeminiTokenStore.kt"
SCREEN="$APP/MagicPhoneScreen.kt"
GRADLE="$ROOT/files/app/build.gradle.kts"
grep -Fq 'const val URL = "wss://ai.magics.site/call/ws?mode=phone"' "$ENDPOINT"
grep -Fq 'return listOf(PROTOCOL, "magic-agent.$encoded")' "$ENDPOINT"
grep -Fq 'private fun validToken(value: String?): Boolean = MagicLiveHandshake.isDeviceToken(value)' "$ENDPOINT"
grep -Fq '.url(MagicLiveHandshake.URL)' "$ENDPOINT"
grep -Fq 'builder.header("X-Magic-Phone-Caller", it)' "$ENDPOINT"
grep -Fq 'fun normalizeCallerNumber(value: String?): String?' "$ENDPOINT"
grep -Fq 'Regex("^mpa_[a-f0-9]{48}$")' "$TOKEN"
grep -Fq '0.5.4-direct' "$GRADLE"
grep -Fq 'versionCode = 12' "$GRADLE"
if grep -Eq 'phone[.]magics[.]site|magic-auth[.]|LEGACY_URL|BRIDGE_ACCESS_TOKEN|Cloudflare' "$ENDPOINT"; then
  echo "FAILED: legacy Worker reference in Android endpoint" >&2; exit 1
fi
grep -Fq 'توکن اختصاصی دستگاه' "$SCREEN"
echo "PASS: Android direct-only token and endpoint contracts"
