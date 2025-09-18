#!/usr/bin/env bash
set -euo pipefail

# Usage: ./unzip-quarkus.sh 3.26.4
[[ $# -eq 1 ]] || { echo "Usage: $0 <version>"; exit 1; }

v="$1"
zipfile="/tmp/quarkus-${v}.zip"
outdir="/tmp/quarkus-${v}"

if [[ ! -f "$zipfile" ]]; then
  echo "Error: $zipfile does not exist. Run download script first." >&2
  exit 1
fi

mkdir -p "$outdir"
unzip -q "$zipfile" -d /tmp

echo "Extracted to: $outdir"
