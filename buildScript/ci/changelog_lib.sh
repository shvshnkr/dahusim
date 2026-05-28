#!/usr/bin/env bash
# Shared release notes: CHANGELOG.ru.md [Unreleased] + git since last app-update promote.
# Source: buildScript/ci/changelog_lib.sh

set -euo pipefail

CHANGELOG_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$CHANGELOG_LIB_DIR/../.." && pwd)"
DEFAULT_CHANGELOG_FILE="${REPO_ROOT}/docs/changelog/CHANGELOG.ru.md"

changelog_file() {
  printf '%s\n' "${CHANGELOG_FILE:-$DEFAULT_CHANGELOG_FILE}"
}

# Extract markdown body for ## [Section] until next ## heading.
changelog_extract_section() {
  local section="$1"
  local file="$2"
  if [ ! -f "$file" ]; then
    return 0
  fi
  awk -v section="$section" '
    BEGIN { found=0 }
    /^## \[/ {
      if (found) { exit }
      title = $0
      sub(/^## \[/, "", title)
      sub(/\].*$/, "", title)
      if (title == section) { found=1; next }
    }
    found { print }
  ' "$file" | sed '/^---$/d' | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}'
}

changelog_read_at_ref() {
  local ref="${1:-HEAD}"
  local dest="${2:-$(changelog_file)}"
  if [ "$ref" = "HEAD" ] || [ "$ref" = "WORKTREE" ]; then
    if [ -f "$dest" ]; then
      cat "$dest"
      return 0
    fi
    return 1
  fi
  if git -C "$REPO_ROOT" cat-file -e "${ref}:docs/changelog/CHANGELOG.ru.md" 2>/dev/null; then
    git -C "$REPO_ROOT" show "${ref}:docs/changelog/CHANGELOG.ru.md"
    return 0
  fi
  return 1
}

changelog_unreleased_body() {
  local ref="${1:-WORKTREE}"
  local file
  file="$(changelog_file)"
  local tmp
  tmp="$(mktemp)"
  if [ "$ref" = "WORKTREE" ] || [ "$ref" = "HEAD" ]; then
    if [ -f "$file" ]; then
      changelog_extract_section "Unreleased" "$file"
    fi
  elif changelog_read_at_ref "$ref" >"$tmp" 2>/dev/null; then
    changelog_extract_section "Unreleased" "$tmp"
  fi
  rm -f "$tmp"
}

# True if section has at least one "- " bullet.
changelog_section_has_bullets() {
  local body="$1"
  printf '%s\n' "$body" | grep -qE '^[[:space:]]*-[[:space:]]+.'
}

resolve_promote_since_ref() {
  local head_sha="${1:-HEAD}"
  local repo="${GITHUB_REPOSITORY:-}"
  local token="${GH_TOKEN:-}"

  if [ -n "${SINCE_REF:-}" ]; then
    printf '%s\n' "$SINCE_REF"
    return 0
  fi

  if [ -n "$repo" ] && [ -n "$token" ]; then
    local url="https://github.com/${repo}/releases/download/app-update-channel/app-update.json"
    local json
    json="$(curl -fsSL -H "Authorization: Bearer ${token}" -H "Accept: application/json" "$url" 2>/dev/null || true)"
    if [ -n "$json" ]; then
      local commit
      commit="$(printf '%s' "$json" | jq -r '.sourceCommit // empty' 2>/dev/null || true)"
      if [ -n "$commit" ] && [ "$commit" != "null" ]; then
        printf '%s\n' "$commit"
        return 0
      fi
    fi
  fi

  if [ -n "$repo" ] && command -v gh >/dev/null 2>&1; then
    local run_sha
    run_sha="$(gh run list --repo "$repo" --workflow "promote-app-update.yml" --branch main --status success --limit 1 --json headSha --jq '.[0].headSha' 2>/dev/null || true)"
    if [ -n "$run_sha" ] && [ "$run_sha" != "null" ]; then
      printf '%s\n' "$run_sha"
      return 0
    fi
  fi

  git -C "$REPO_ROOT" rev-parse "${head_sha}~30" 2>/dev/null || git -C "$REPO_ROOT" rev-parse HEAD~30
}

git_notes_since() {
  local since_ref="$1"
  local head_ref="${2:-HEAD}"
  if [ "$since_ref" = "$head_ref" ]; then
    return 0
  fi
  if ! git -C "$REPO_ROOT" rev-parse --verify "${since_ref}^{commit}" >/dev/null 2>&1; then
    return 1
  fi
  if ! git -C "$REPO_ROOT" rev-parse --verify "${head_ref}^{commit}" >/dev/null 2>&1; then
    head_ref="HEAD"
  fi
  local count
  count="$(git -C "$REPO_ROOT" rev-list --count "${since_ref}..${head_ref}" 2>/dev/null || echo 0)"
  if [ "${count:-0}" -eq 0 ]; then
    return 1
  fi
  echo "### Изменения с прошлого обновления канала"
  echo
  git -C "$REPO_ROOT" log --no-merges --pretty=format:'- %s (%h)' "${since_ref}..${head_ref}"
  echo
}

merge_release_notes() {
  local head_ref="${HEAD_SHA:-HEAD}"
  local override="${RELEASE_NOTES_OVERRIDE:-}"
  local ver_name="${VERSION_NAME:-}"
  local ver_code="${VERSION_CODE:-}"
  local run_id="${RUN_ID:-}"
  local head_short
  head_short="$(git -C "$REPO_ROOT" rev-parse --short "$head_ref" 2>/dev/null || echo unknown)"

  if [ -n "$override" ]; then
    printf '%s\n' "$override"
    return 0
  fi

  local unreleased
  unreleased="$(changelog_unreleased_body "$head_ref" | sed '/^[[:space:]]*$/d')"
  if changelog_section_has_bullets "$unreleased"; then
    printf '%s\n' "$unreleased"
    return 0
  fi

  local since_ref
  since_ref="$(resolve_promote_since_ref "$head_ref")"
  local git_block
  git_block="$(git_notes_since "$since_ref" "$head_ref" 2>/dev/null || true)"
  if [ -n "$git_block" ]; then
    printf '%s\n' "$git_block"
    return 0
  fi

  {
    echo "Сборка **${ver_name:-?}** (${ver_code:-?}), commit \`${head_short}\`."
    if [ -n "$run_id" ]; then
      echo
      echo "CI run: \`${run_id}\`."
    fi
    echo
    echo "_Список изменений не заполнен — допишите секцию \`[Unreleased]\` в docs/changelog/CHANGELOG.ru.md._"
  }
}

changelog_verify() {
  local head_ref="${HEAD_SHA:-HEAD}"
  local unreleased
  unreleased="$(changelog_unreleased_body "$head_ref")"
  if changelog_section_has_bullets "$unreleased"; then
    echo "OK: [Unreleased] has bullet items."
    return 0
  fi
  local since_ref
  since_ref="$(resolve_promote_since_ref "$head_ref")"
  local count
  count="$(git -C "$REPO_ROOT" rev-list --count "${since_ref}..${head_ref}" 2>/dev/null || echo 0)"
  if [ "${count:-0}" -gt 0 ]; then
    echo "OK: ${count} commit(s) since last promote boundary (${since_ref:0:7})."
    return 0
  fi
  echo "WARN: empty [Unreleased] and no commits since last promote."
  return 1
}

CHANGELOG_REFRESH_MARKER="<!-- ci:git-draft -->"

changelog_refresh() {
  local head_ref="${HEAD_SHA:-HEAD}"
  local file
  file="$(changelog_file)"
  local since_ref
  since_ref="$(resolve_promote_since_ref "$head_ref")"
  local draft_file
  draft_file="$(mktemp)"
  if ! git_notes_since "$since_ref" "$head_ref" >"$draft_file" 2>/dev/null; then
    rm -f "$draft_file"
    echo "Nothing to refresh (no new commits)."
    return 0
  fi
  if [ ! -f "$file" ]; then
    mkdir -p "$(dirname "$file")"
    printf '%s\n' "# Changelog (RU)" "" "## [Unreleased]" "" >"$file"
  fi
  local out
  out="$(mktemp)"
  if grep -qF "$CHANGELOG_REFRESH_MARKER" "$file"; then
    awk -v marker="$CHANGELOG_REFRESH_MARKER" -v draftf="$draft_file" '
      $0 == marker {
        print
        while ((getline line < draftf) > 0) print line
        skip = 1
        next
      }
      skip && /^<!-- \/ci:git-draft -->/ { print; skip = 0; next }
      !skip { print }
    ' "$file" >"$out"
  else
    {
      cat "$file"
      echo ""
      echo "$CHANGELOG_REFRESH_MARKER"
      cat "$draft_file"
      echo "<!-- /ci:git-draft -->"
    } >"$out"
  fi
  mv "$out" "$file"
  rm -f "$draft_file"
  echo "Updated $file with git draft under [Unreleased]."
}

changelog_seal() {
  local file
  file="$(changelog_file)"
  local ver_name ver_code run_id
  ver_name="$(grep '^VERSION_NAME=' "$REPO_ROOT/husi.properties" | cut -d= -f2-)"
  ver_code="$(grep '^VERSION_CODE=' "$REPO_ROOT/husi.properties" | cut -d= -f2-)"
  run_id="${RUN_ID:-manual}"
  local date
  date="$(date -u +%Y-%m-%d)"
  local body
  body="$(changelog_unreleased_body "WORKTREE" | sed '/^[[:space:]]*$/d')"
  if ! changelog_section_has_bullets "$body"; then
    echo "Nothing to seal: [Unreleased] has no bullet items."
    return 1
  fi
  local block_file history_file out
  block_file="$(mktemp)"
  history_file="$(mktemp)"
  out="$(mktemp)"
  {
    echo "### Promoted ${date} · ${ver_name} (${ver_code}) · run ${run_id}"
    echo "$body"
    echo ""
  } >"$block_file"
  if [ -f "$file" ] && grep -q '^## История promote' "$file"; then
    awk '
      /^## История promote/ { print; while ((getline l < bf) > 0) print l; hist=1; next }
      { print }
    ' bf="$block_file" "$file" >"$out"
  else
    {
      cat "$file"
      echo ""
      echo "## История promote"
      echo ""
      cat "$block_file"
    } >"$out"
  fi
  awk '
    /^## \[Unreleased\]/ {
      print
      print ""
      print "### Добавлено"
      print ""
      in_unrel = 1
      next
    }
    in_unrel && /^## / { in_unrel = 0 }
    in_unrel { next }
    { print }
  ' "$out" >"${file}.tmp"
  mv "${file}.tmp" "$file"
  rm -f "$block_file" "$history_file" "$out"
  echo "Sealed [Unreleased] into history in $file"
}
