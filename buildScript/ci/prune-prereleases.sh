#!/usr/bin/env bash
# Delete CI pre-release trios (android/linux/windows/rolling-<run_id>), keep one green run_id.
# Usage: buildScript/ci/prune-prereleases.sh [--keep-run-id ID] [--dry-run] [--limit N]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=changelog_lib.sh
source "$SCRIPT_DIR/changelog_lib.sh"

GH_REPO="${GH_REPO:-}"

resolve_gh_repo() {
  if [ -n "$GH_REPO" ]; then
    printf '%s\n' "$GH_REPO"
    return 0
  fi
  if command -v gh >/dev/null 2>&1; then
    gh repo view --json nameWithOwner --jq .nameWithOwner 2>/dev/null && return 0
  fi
  local url
  url="$(git -C "$REPO_ROOT" remote get-url github 2>/dev/null || true)"
  case "$url" in
    *github.com*/*)
      url="${url%.git}"
      url="${url#*github.com/}"
      url="${url#*:}"
      printf '%s\n' "$url"
      return 0
      ;;
  esac
  echo "Cannot resolve GitHub repo (set GH_REPO or add remote github)" >&2
  return 1
}

usage() {
  cat <<'EOF'
Usage: prune-prereleases.sh [options]

Deletes CI pre-release tags from failed or superseded all-platforms builds.
Never touches app-update-channel or other non-CI tags.

Options:
  --keep-run-id ID   Keep android/linux/windows (and legacy rolling) for this run_id
                     (default: latest successful "All platforms" on main)
  --dry-run          Print tags that would be deleted, do not delete
  --limit N          Scan at most N recent releases (default: 80)

Examples:
  buildScript/ci/prune-prereleases.sh
  buildScript/ci/prune-prereleases.sh --keep-run-id 26617034812
  buildScript/ci/prune-prereleases.sh --dry-run
  buildScript/ci/gh-workflow.sh prune-prereleases
EOF
}

is_ci_prerelease_tag() {
  local tag="$1"
  [[ "$tag" =~ ^android-[0-9]+$ ]] && return 0
  [[ "$tag" =~ ^linux-desktop-linux-amd64-[0-9]+$ ]] && return 0
  [[ "$tag" =~ ^windows-desktop-windows-amd64-[0-9]+$ ]] && return 0
  [[ "$tag" =~ ^rolling-[0-9]+$ ]] && return 0
  return 1
}

tags_for_run_id() {
  local rid="$1"
  printf '%s\n' \
    "android-${rid}" \
    "linux-desktop-linux-amd64-${rid}" \
    "windows-desktop-windows-amd64-${rid}" \
    "rolling-${rid}"
}

resolve_keep_run_id() {
  local repo="$1"
  gh run list --repo "$repo" \
    --workflow all-platforms-build.yml \
    --branch main \
    --limit 30 \
    --json databaseId,conclusion \
    --jq '.[] | select(.conclusion == "success") | .databaseId' \
    | head -n1
}

main() {
  local keep_rid="" dry_run=false limit=80
  while [ $# -gt 0 ]; do
    case "$1" in
      --keep-run-id) keep_rid="$2"; shift 2 ;;
      --dry-run) dry_run=true; shift ;;
      --limit) limit="$2"; shift 2 ;;
      -h|--help|help) usage; exit 0 ;;
      *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
    esac
  done

  local repo
  repo="$(resolve_gh_repo)"

  if [ -z "$keep_rid" ]; then
    keep_rid="$(resolve_keep_run_id "$repo")"
    if [ -z "$keep_rid" ]; then
      echo "No successful all-platforms run on main; use --keep-run-id" >&2
      exit 1
    fi
    echo "Keeping pre-release trio for run_id=${keep_rid}"
  else
    echo "Keeping pre-release trio for run_id=${keep_rid} (explicit)"
  fi

  local -a keep_tags=()
  while IFS= read -r t; do
    keep_tags+=("$t")
  done < <(tags_for_run_id "$keep_rid")

  local deleted=0 skipped=0
  while IFS= read -r tag; do
    [ -n "$tag" ] || continue
    if ! is_ci_prerelease_tag "$tag"; then
      continue
    fi
    local keep=false
    for kt in "${keep_tags[@]}"; do
      if [ "$tag" = "$kt" ]; then
        keep=true
        break
      fi
    done
    if [ "$keep" = true ]; then
      echo "keep  $tag"
      skipped=$((skipped + 1))
      continue
    fi
    if [ "$dry_run" = true ]; then
      echo "would delete $tag"
    else
      if gh release delete "$tag" --repo "$repo" --yes --cleanup-tag 2>/dev/null; then
        echo "deleted $tag"
        deleted=$((deleted + 1))
      else
        echo "skip  $tag (not found or not deletable)" >&2
      fi
    fi
  done < <(gh release list --repo "$repo" --limit "$limit" --json tagName --jq '.[].tagName')

  if [ "$dry_run" = true ]; then
    echo "Dry-run complete (kept run_id=${keep_rid})."
  else
    echo "Done: deleted=${deleted}, kept tags for run_id=${keep_rid}."
  fi
}

main "$@"
