#!/usr/bin/env bash
set -euo pipefail

# Usage: ./download-quarkus.sh 3.26.4
[[ $# -eq 1 ]] || { echo "Usage: $0 <version>"; exit 1; }

v="$1"
url="https://github.com/quarkusio/quarkus/archive/refs/tags/${v}.zip"
out="/tmp/quarkus-${v}.zip"

curl -fsSL --retry 3 "$url" -o "$out"
echo "Saved to $out"
