#!/usr/bin/env bash
set -euo pipefail

echo "========================================"
echo "SETTING UP FOR EXPERIMENTS"
echo "========================================"
echo ""

# 1. Build the JAR
echo "Step 1/3: Building chappie-quarkus-rag JAR..."
cd "$(dirname "$0")/.."
./mvnw clean package -DskipTests
JAR="target/chappie-quarkus-rag-999-SNAPSHOT.jar"
if [[ ! -f "$JAR" ]]; then
  echo "ERROR: JAR not built: $JAR"
  exit 1
fi

# Copy to /tmp for the scripts
cp "$JAR" /tmp/
echo "✅ JAR copied to /tmp/"

# 2. Download Quarkus 3.30.6
echo ""
echo "Step 2/3: Downloading Quarkus 3.30.6..."
cd scripts
./download.sh 3.30.6 || echo "Already downloaded"

# 3. Unzip Quarkus
echo ""
echo "Step 3/3: Unzipping Quarkus 3.30.6..."
./unzip.sh 3.30.6 || echo "Already unzipped"

echo ""
echo "========================================"
echo "SETUP COMPLETE!"
echo "========================================"
echo ""
echo "✅ JAR:          /tmp/chappie-quarkus-rag-999-SNAPSHOT.jar"
echo "✅ Quarkus repo: /tmp/quarkus-3.30.6"
echo ""
echo "You're ready to run experiments!"
echo ""
echo "Try your first experiment:"
echo "  cd scripts"
echo "  ./experiment-ingest.sh 3.30.6 1000 300 overlap-30pct"
echo ""
echo "See EXPERIMENT-WORKFLOW.md for full guide."
echo "========================================"
