#!/usr/bin/env bash
set -euo pipefail

# Usage: ./chappie-ingest.sh <quarkus-version> [--latest]
[[ $# -ge 1 ]] || { echo "Usage: $0 <quarkus-version> [--latest]"; exit 1; }
V="$1"
LATEST_FLAG=""
[[ "${2:-}" == "--latest" ]] && LATEST_FLAG="--latest"

# Pick the most recent JAR downloaded to /tmp
JAR="$(ls -t /tmp/chappie-quarkus-rag-*.jar 2>/dev/null | head -1 || true)"
[[ -n "$JAR" && -f "$JAR" ]] || { echo "Error: chappie-quarkus-rag JAR not found in /tmp"; exit 2; }

REPO="/tmp/quarkus-$V"
[[ -d "$REPO" ]] || { echo "Error: $REPO does not exist. Unzip the Quarkus $V sources first."; exit 3; }

OUT1="/tmp/quarkus-$V-docs.json"
OUT2="/tmp/quarkus-$V-docs.enriched.json"

echo "Using JAR:  $JAR"
echo "Repo root:  $REPO"
[[ -n "$LATEST_FLAG" ]] && echo "Will also tag image as 'latest'."

# 1) find
java -jar "$JAR" find \
  --repo-root "$REPO" \
  --quarkus-version "$V" \
  --out "$OUT1"

# 2) manifest-enrich
java -jar "$JAR" manifest-enrich \
  --repo-root "$REPO" \
  --in "$OUT1" \
  --out "$OUT2"

# 3) bake-image (requires env vars)
: "${REGISTRY_USERNAME:?Set REGISTRY_USERNAME in your environment}"
: "${REGISTRY_PASSWORD:?Set REGISTRY_PASSWORD in your environment}"

java -jar "$JAR" bake-image \
  --repo-root "$REPO" \
  --in "$OUT2" \
  --quarkus-version "$V" \
  --push \
  --registry-username "$REGISTRY_USERNAME" \
  --registry-password "$REGISTRY_PASSWORD" \
  ${LATEST_FLAG:+$LATEST_FLAG}

echo "Done."
echo "Outputs:"
echo "  $OUT1"
echo "  $OUT2"
