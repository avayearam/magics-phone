#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DEST="$HERE/files/app/src/main/res/font"
mkdir -p "$DEST"
# Vazirmatn by Saber Rastikerdar (SIL Open Font License 1.1)
# Pin exact Git blob identities; fail on changed binaries.
for item in \
 "Vazirmatn-Regular.ttf:vazirmatn_regular.ttf:64e4a8189557a17b63fd7a77794207483e592972" \
 "Vazirmatn-Bold.ttf:vazirmatn_bold.ttf:efa9b095da01d299310bdcae293e24e657fe6da9"
do
 IFS=: read -r remote local sha <<< "$item"
 path="$DEST/$local"
 if [ ! -f "$path" ]; then
   curl --fail --location --retry 3 --connect-timeout 12 \
     "https://raw.githubusercontent.com/rastikerdar/vazirmatn/master/fonts/ttf/$remote" -o "$path"
 fi
 bytes="$(wc -c < "$path" | tr -d ' ')"
 actual="$({ printf 'blob %s\0' "$bytes"; cat "$path"; } | sha1sum | cut -d' ' -f1)"
 if [ "$actual" != "$sha" ]; then
   echo "Font verification failed: $remote" >&2
   rm -f "$path"
   exit 1
 fi
done
echo "Verified Vazirmatn Regular/Bold resources."
