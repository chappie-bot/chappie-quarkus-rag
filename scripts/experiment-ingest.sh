#!/usr/bin/env bash
set -euo pipefail

# Usage: ./experiment-ingest.sh <quarkus-version> <chunk-size> <chunk-overlap> <experiment-name>
# Example: ./experiment-ingest.sh 3.30.6 1000 300 overlap-30pct

[[ $# -eq 4 ]] || {
  echo "Usage: $0 <quarkus-version> <chunk-size> <chunk-overlap> <experiment-name>"
  echo ""
  echo "Examples:"
  echo "  $0 3.30.6 1000 300 overlap-30pct     # Increase overlap to 30%"
  echo "  $0 3.30.6 800  200 smaller-chunks    # Smaller chunks"
  echo "  $0 3.30.6 1200 200 larger-chunks     # Larger chunks"
  exit 1
}

V="$1"
CHUNK_SIZE="$2"
CHUNK_OVERLAP="$3"
EXPERIMENT_NAME="$4"

# Pick the most recent JAR downloaded to /tmp
JAR="$(ls -t /tmp/chappie-quarkus-rag-*.jar 2>/dev/null | head -1 || true)"
[[ -n "$JAR" && -f "$JAR" ]] || { echo "Error: chappie-quarkus-rag JAR not found in /tmp"; exit 2; }

REPO="/tmp/quarkus-$V"
[[ -d "$REPO" ]] || { echo "Error: $REPO does not exist. Run download.sh and unzip.sh first."; exit 3; }

OUT1="/tmp/quarkus-$V-docs.json"
OUT2="/tmp/quarkus-$V-docs.enriched.json"

IMAGE_TAG="ghcr.io/quarkusio/chappie-ingestion-quarkus:test-$EXPERIMENT_NAME"

echo "========================================"
echo "EXPERIMENT: $EXPERIMENT_NAME"
echo "========================================"
echo "Using JAR:       $JAR"
echo "Repo root:       $REPO"
echo "Chunk size:      $CHUNK_SIZE"
echo "Chunk overlap:   $CHUNK_OVERLAP"
echo "Image tag:       $IMAGE_TAG"
echo "========================================"
echo ""

# Check if manifest files exist, if not create them
if [[ ! -f "$OUT1" ]] || [[ ! -f "$OUT2" ]]; then
  echo "==> Manifest files not found, creating them..."

  # 1) find
  echo "Step 1/3: Finding documentation files..."
  java -jar "$JAR" find \
    --repo-root "$REPO" \
    --quarkus-version "$V" \
    --out "$OUT1"

  # 2) manifest-enrich
  echo "Step 2/3: Enriching manifest..."
  java -jar "$JAR" manifest-enrich \
    --repo-root "$REPO" \
    --in "$OUT1" \
    --out "$OUT2"
else
  echo "==> Using existing manifest files:"
  echo "    $OUT1"
  echo "    $OUT2"
fi

# 3) bake-image with custom parameters (local Docker only, no push)
echo "Step 3/3: Baking image with custom chunk parameters..."
echo ""
java -jar "$JAR" bake-image \
  --repo-root "$REPO" \
  --in "$OUT2" \
  --quarkus-version "$V" \
  --chunk-size "$CHUNK_SIZE" \
  --chunk-overlap "$CHUNK_OVERLAP"

echo ""
echo "========================================"
echo "EXPERIMENT COMPLETE"
echo "========================================"
echo "Image created: $IMAGE_TAG"
echo ""
echo "Next steps:"
echo "1. Tag your image:"
echo "   docker tag ghcr.io/quarkusio/chappie-ingestion-quarkus:$V $IMAGE_TAG"
echo ""
echo "2. Test it in chappie-server:"
echo "   cd ../chappie-server"
echo "   ./mvnw test -Dtest=RagComparisonTest -Drag.image=$IMAGE_TAG"
echo ""
echo "3. Review the report:"
echo "   cat target/rag-comparison-report.md"
echo "========================================"
