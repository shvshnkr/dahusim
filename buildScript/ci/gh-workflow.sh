#!/usr/bin/env bash
# Local wrapper for dahusim GitHub Actions (build, promote, changelog).
# Usage: buildScript/ci/gh-workflow.sh <command> [options]

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
Usage: gh-workflow.sh <command> [options]

Commands:
  build [--android|--linux|--windows] [--wait]   # manual pre-release (after CI matrices; not auto on test/CI-only pushes)
  status [--run-id ID]
  promote [--run-id ID] [--dry-run] [--mandatory] [--min-version-code N]
          [--notes TEXT | --notes-file PATH | --from-changelog]
          [--no-changelog]
  changelog draft [--en] [--since REF]
  changelog refresh
  changelog verify
  changelog seal [--run-id ID]

Examples:
  gh-workflow.sh build --wait
  gh-workflow.sh changelog verify
  gh-workflow.sh promote --from-changelog --dry-run
  gh-workflow.sh promote --run-id 26577000000
EOF
}

cmd_build() {
  local android=true linux=true windows=true wait=false
  while [ $# -gt 0 ]; do
    case "$1" in
      --android) android=true; linux=false; windows=false ;;
      --linux) android=false; linux=true; windows=false ;;
      --windows) android=false; linux=false; windows=true ;;
      --wait) wait=true ;;
      *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
    shift
  done
  local repo
  repo="$(resolve_gh_repo)"
  gh workflow run all-platforms-build.yml --repo "$repo" --ref main \
    -f "android=${android}" -f "linux_desktop=${linux}" -f "windows_desktop=${windows}"
  echo "Triggered all-platforms-build.yml on $repo"
  if [ "$wait" = true ]; then
    sleep 5
    local run_id
    run_id="$(gh run list --repo "$repo" --workflow all-platforms-build.yml --branch main --limit 1 --json databaseId --jq '.[0].databaseId')"
    gh run watch "$run_id" --repo "$repo" --exit-status
  fi
}

cmd_status() {
  local run_id="${1:-}"
  local repo
  repo="$(resolve_gh_repo)"
  if [ -z "$run_id" ]; then
    gh run list --repo "$repo" --workflow all-platforms-build.yml --branch main --limit 5
    return 0
  fi
  gh run view "$run_id" --repo "$repo"
}

cmd_promote() {
  local run_id="" dry_run=false mandatory=false min_code="" notes="" notes_file="" from_changelog=true use_changelog=true
  while [ $# -gt 0 ]; do
    case "$1" in
      --run-id) run_id="$2"; shift 2 ;;
      --dry-run) dry_run=true; shift ;;
      --mandatory) mandatory=true; shift ;;
      --min-version-code) min_code="$2"; shift 2 ;;
      --notes) notes="$2"; from_changelog=false; shift 2 ;;
      --notes-file) notes_file="$2"; from_changelog=false; shift 2 ;;
      --from-changelog) from_changelog=true; shift ;;
      --no-changelog) use_changelog=false; from_changelog=false; shift ;;
      *) echo "Unknown promote option: $1" >&2; exit 1 ;;
    esac
  done
  local repo
  repo="$(resolve_gh_repo)"
  local args=()
  [ -n "$run_id" ] && args+=(-f "run_id=${run_id}")
  args+=(-f "offer_update=true")
  args+=(-f "mandatory=${mandatory}")
  [ -n "$min_code" ] && args+=(-f "min_version_code=${min_code}")
  if [ "$dry_run" = true ]; then
    args+=(-f "publish=false")
  else
    args+=(-f "publish=true")
  fi
  if [ -n "$notes_file" ]; then
    notes="$(cat "$notes_file")"
  fi
  if [ -n "$notes" ]; then
    args+=(-f "release_notes=${notes}")
    args+=(-f "use_changelog=false")
  elif [ "$from_changelog" = true ] && [ "$use_changelog" = true ]; then
    args+=(-f "use_changelog=true")
  else
    args+=(-f "use_changelog=false")
  fi
  gh workflow run promote-app-update.yml --repo "$repo" --ref main "${args[@]}"
  echo "Triggered promote-app-update.yml on $repo"
}

cmd_changelog_draft() {
  local en=false since=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --en) en=true ;;
      --since) since="$2"; shift ;;
      *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
    shift
  done
  local head_ref="${HEAD_SHA:-HEAD}"
  local since_ref="${since:-$(resolve_promote_since_ref "$head_ref")}"
  if [ "$en" = true ]; then
    git -C "$REPO_ROOT" log --no-merges --pretty=format:'- %s (%h)' "${since_ref}..${head_ref}"
  else
    git_notes_since "$since_ref" "$head_ref" || true
  fi
}

cmd_changelog_seal() {
  local run_id="manual"
  while [ $# -gt 0 ]; do
    case "$1" in
      --run-id) run_id="$2"; shift 2 ;;
      *) shift ;;
    esac
  done
  RUN_ID="$run_id" changelog_seal
}

main() {
  local cmd="${1:-}"
  shift || true
  case "$cmd" in
    build) cmd_build "$@" ;;
    status) cmd_status "$@" ;;
    promote) cmd_promote "$@" ;;
    changelog)
      local sub="${1:-}"
      shift || true
      case "$sub" in
        draft) cmd_changelog_draft "$@" ;;
        draft-en) cmd_changelog_draft --en "$@" ;;
        refresh) changelog_refresh ;;
        verify) changelog_verify ;;
        seal) cmd_changelog_seal "$@" ;;
        *) usage; exit 1 ;;
      esac
      ;;
    -h|--help|help|"") usage ;;
    *) echo "Unknown command: $cmd" >&2; usage; exit 1 ;;
  esac
}

main "$@"
