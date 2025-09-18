#!/usr/bin/env bash
set -euo pipefail

# Logic:
# - Get highest numeric tag on GHCR: ghcr.io/quarkusio/chappie-ingestion-quarkus
# - Get latest Quarkus release tag from GitHub (non-prerelease)
# - If latest release > highest GHCR tag, run ./run-all.sh <release-version>

cd "$(dirname "$0")"

ORG="quarkusio"
PKG="chappie-ingestion-quarkus"
REPO="quarkusio/quarkus"
RUNNER="./run-all.sh"

# Prefer REGISTRY_PASSWORD; accept common variants; fall back to GITHUB_TOKEN/GH_TOKEN
TOKEN="${REGISTRY_PASSWORD:-${REGISTY_PASSWORD:-${GITHUB_TOKEN:-${GH_TOKEN:-}}}}"

api() {
  local url="$1"
  if [[ -n "${TOKEN:-}" ]]; then
    curl -fsSL \
      -H "Authorization: Bearer $TOKEN" \
      -H "X-GitHub-Api-Version: 2022-11-28" \
      "$url"
  else
    curl -fsSL "$url"
  fi
}

# --- 1) Highest built version on GHCR (by tags) ---
# Walk pages to collect all tags; keep numeric-looking tags; pick max via sort -V
get_highest_ghcr_tag() {
  local page=1 all='[]'
  while :; do
    local chunk
    chunk="$(api "https://api.github.com/orgs/${ORG}/packages/container/${PKG}/versions?per_page=100&page=${page}")"
    [[ "$(jq -r 'length' <<<"$chunk")" -eq 0 ]] && break
    all="$(jq -s 'add' <(printf '%s' "$all") <(printf '%s' "$chunk"))"
    page=$((page+1))
  done

  # Extract tags, drop "v" prefix if present, keep purely numeric semver, dedupe, sort -V, pick last
  jq -r '.[].metadata.container.tags[]?' <<<"$all" 2>/dev/null \
    | sed 's/^v//' \
    | grep -E '^[0-9]+(\.[0-9]+)*$' \
    | sort -Vu \
    | tail -1
}

# --- 2) Latest Quarkus release tag (non-prerelease) ---
get_latest_quarkus_release() {
  # Use releases/latest (non-prerelease). Fallback to first tag if needed.
  local tag
  tag="$(api "https://api.github.com/repos/${REPO}/releases/latest" | jq -r '.tag_name // empty')"
  if [[ -z "$tag" || "$tag" == "null" ]]; then
    tag="$(api "https://api.github.com/repos/${REPO}/tags?per_page=1" | jq -r '.[0].name // empty')"
  fi
  printf '%s\n' "$tag" | sed 's/^v//'
}

max_ghcr="$(get_highest_ghcr_tag || true)"
latest_release="$(get_latest_quarkus_release || true)"

[[ -n "$latest_release" ]] || { echo "Could not determine latest Quarkus release."; exit 2; }

echo "Highest GHCR tag: ${max_ghcr:-<none>}"
echo "Latest Quarkus release: ${latest_release}"

# If no GHCR builds yet, we definitely need to run
if [[ -z "${max_ghcr:-}" ]]; then
  need_version="$latest_release"
else
  # Compare with sort -V
  top="$(printf '%s\n%s\n' "$max_ghcr" "$latest_release" | sort -V | tail -1)"
  if [[ "$top" != "$latest_release" || "$latest_release" == "$max_ghcr" ]]; then
    echo "Nothing to do: GHCR already at ${max_ghcr} (>= ${latest_release})."
    exit 0
  fi
  need_version="$latest_release"
fi

echo "Newer release detected: $need_version — running $RUNNER"
[[ -x "$RUNNER" ]] || { echo "Missing or not executable: $RUNNER"; exit 3; }

"$RUNNER" "$need_version"
