#!/usr/bin/env bash
set -euo pipefail

# Usage: ./run-all.sh <quarkus-version>
# Example: ./run-all.sh 3.26.3
# Note: is-latest.sh uses REGISTRY_PASSWORD (GitHub PAT with read:packages)

[[ $# -ge 1 ]] || { echo "Usage: $0 <quarkus-version>"; exit 1; }
V="$1"

cd "$(dirname "$0")"

NEEDED=(get-chappie-cli.sh download.sh unzip.sh chappie-ingest.sh is-latest.sh)
for s in "${NEEDED[@]}"; do
  [[ -x "./$s" ]] || { echo "Error: missing or not executable: ./$s"; exit 2; }
done

echo "==> 1/5: Determine if $V is latest (GitHub Packages)"
LATEST_ARG=""
if ./is-latest.sh "$V"; then
  echo "Version $V IS latest -> will pass --latest"
  LATEST_ARG="--latest"
else
  rc=$?
  if [[ $rc -eq 1 ]]; then
    echo "Version $V is NOT latest -> will NOT tag latest"
  else
    echo "is-latest.sh failed (exit $rc). Fix your token/env and retry."
    exit $rc
  fi
fi

echo "==> 2/5: Fetch chappie CLI JAR"
./get-chappie-cli.sh

echo "==> 3/5: Download Quarkus $V"
./download.sh "$V"

echo "==> 4/5: Unzip Quarkus $V"
./unzip.sh "$V"

echo "==> 5/5: Ingest with chappie for $V ${LATEST_ARG:+(also tag latest)}"
if [[ -n "$LATEST_ARG" ]]; then
  ./chappie-ingest.sh "$V" --latest
else
  ./chappie-ingest.sh "$V"
fi

echo "Done."
