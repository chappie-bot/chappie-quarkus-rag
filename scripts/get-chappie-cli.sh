#!/usr/bin/env bash
set -euo pipefail

BASE="https://repo1.maven.org/maven2/org/chappie-bot/chappie-quarkus-rag"
META="$BASE/maven-metadata.xml"

# fetch metadata
xml="$(curl -fsSL "$META")" || { echo "Failed to fetch $META" >&2; exit 1; }

# prefer <release>, then <latest>, else last <version> entry
ver="$(
  printf '%s' "$xml" | sed -n 's:.*<release>\(.*\)</release>.*:\1:p'
)"
if [[ -z "${ver}" ]]; then
  ver="$(printf '%s' "$xml" | sed -n 's:.*<latest>\(.*\)</latest>.*:\1:p')"
fi
if [[ -z "${ver}" ]]; then
  ver="$(printf '%s' "$xml" | grep -oE '<version>[^<]+' | sed 's/<version>//' | tail -1)"
fi

[[ -n "${ver}" ]] || { echo "Could not determine latest version from metadata." >&2; exit 2; }

jar_url="$BASE/$ver/chappie-quarkus-rag-$ver.jar"
out="/tmp/chappie-quarkus-rag-$ver.jar"

echo "Latest version: $ver"
echo "Downloading $jar_url -> $out"
curl -fsSL "$jar_url" -o "$out" || { echo "Download failed." >&2; exit 3; }

[[ -s "$out" ]] || { echo "Downloaded file is empty." >&2; exit 4; }
echo "Saved: $out"
