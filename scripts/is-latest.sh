#!/usr/bin/env bash
set -euo pipefail

# Usage: ./is-latest.sh <version>
# Exit codes: 0 = IS latest, 1 = NOT latest, 2+ = error
# Requires: curl, jq

[[ $# -eq 1 ]] || { echo "Usage: $0 <version>"; exit 2; }
INPUT_VER="$1"

ORG="quarkusio"
PKG="chappie-ingestion-quarkus"
API="https://api.github.com/orgs/${ORG}/packages/container/${PKG}/versions?per_page=100"

# Prefer REGISTRY_PASSWORD; accept REGISTY_PASSWORD; then fall back to GITHUB_TOKEN / GH_TOKEN
TOKEN="${REGISTRY_PASSWORD:-${REGISTY_PASSWORD:-${GITHUB_TOKEN:-${GH_TOKEN:-}}}}"
[[ -n "${TOKEN:-}" ]] || { echo "Set REGISTRY_PASSWORD (GitHub PAT with read:packages)"; exit 2; }

# Pull all pages
page=1
all='[]'
while :; do
  chunk="$(curl -fsSL \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "${API}&page=${page}")"
  # Stop if empty page
  [[ "$(jq -r 'length' <<<"$chunk")" -eq 0 ]] && break
  all="$(jq -s 'add' <(printf '%s' "$all") <(printf '%s' "$chunk"))"
  page=$((page+1))
done

# Extract numeric-looking tags, sort version-aware, pick the last as "latest"
mapfile -t TAGS < <(jq -r '.[].metadata.container.tags[]?' <<<"$all" \
  | grep -E '^[0-9]+(\.[0-9]+)*$' \
  | sort -Vu)

if [[ ${#TAGS[@]} -eq 0 ]]; then
  echo "No numeric version tags found."
  exit 3
fi

LATEST="${TAGS[-1]}"

# Optional: warn if input not present among tags
if ! printf '%s\n' "${TAGS[@]}" | grep -qx "$INPUT_VER"; then
  echo "Input '$INPUT_VER' not found. Latest is '$LATEST'."
  exit 1
fi

if [[ "$INPUT_VER" == "$LATEST" ]]; then
  echo "YES: '$INPUT_VER' is the latest."
  exit 0
else
  echo "NO: '$INPUT_VER' is NOT the latest. Latest is '$LATEST'."
  exit 1
fi
