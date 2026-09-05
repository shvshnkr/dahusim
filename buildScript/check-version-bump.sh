#!/usr/bin/env bash
# check-version-bump.sh — preflight-гейт dispatch-сборки (правило bump: AGENTS.md).
# Логика: если с merge-base(main, ref) менялись shippable-исходники, а VERSION_CODE на
# origin/<ref> не поднялся — сборка не запускается. Тест-каталоги исключены (test-only
# коммиты bump не требуют, как и CI paths-filter). ref=main — soft-skip (контролирует
# promote-флоу). Escape для UI-only релизов redesign: --skip.
# В CI аналогичный гейт — .github/workflows/all-platforms-build.yml (VERSION_CODE bump gate).
set -euo pipefail

skip=false; ref=""
while [ $# -gt 0 ]; do
  case "$1" in
    --ref) ref="$2"; shift 2 ;;
    --skip) skip=true; shift ;;
    *) echo "check-version-bump: unknown option $1" >&2; exit 2 ;;
  esac
done
if [ "$skip" = true ]; then echo "VERSION_GATE: skipped (--skip)"; exit 0; fi

ref="${ref:-$(git rev-parse --abbrev-ref HEAD)}"
remote="$(git remote | grep -x github || git remote | head -1 || true)"
[ -n "$remote" ] || { echo "VERSION_GATE: нет remote — пропускаю"; exit 0; }
git fetch --quiet "$remote" "$ref" 2>/dev/null || true
ref_sha="$(git rev-parse --verify --quiet "$remote/$ref" || true)"
[ -n "$ref_sha" ] || { echo "VERSION_GATE: $remote/$ref не найден — пропускаю"; exit 0; }
main_sha="$(git rev-parse --verify --quiet "$remote/main" || git rev-parse --verify --quiet main || true)"
[ -n "$main_sha" ] || { echo "VERSION_GATE: нет main — пропускаю"; exit 0; }
if [ "$ref_sha" = "$main_sha" ]; then
  echo "VERSION_GATE: ref=main — release-флоу контролирует promote; пропускаю"
  exit 0
fi
base="$(git merge-base "$main_sha" "$ref_sha" 2>/dev/null || true)"
[ -n "$base" ] || { echo "VERSION_GATE: нет merge-base(main, $ref) — пропускаю"; exit 0; }

srcs="$(git diff --name-only "$base" "$ref_sha" -- \
  composeApp androidApp library libcore buildSrc gradle gradle.properties \
  settings.gradle.kts build.gradle.kts repositories.gradle.kts launcher plugin run Makefile 2>/dev/null \
  | grep -Ev '/(common|android|desktop|jvm|unit)?[Tt]est/|/src/test/' || true)"
if [ -z "$srcs" ]; then
  echo "VERSION_GATE: shippable-исходники с merge-base не менялись — ok"
  exit 0
fi

vc_base="$(git show "$base:husi.properties" 2>/dev/null | sed -n 's/^VERSION_CODE=//p' | tr -d '[:space:]')"
vc_ref="$(git show "$ref_sha:husi.properties" 2>/dev/null | sed -n 's/^VERSION_CODE=//p' | tr -d '[:space:]')"
echo "VERSION_GATE: sources changed since merge-base; VERSION_CODE ${vc_base:-?} -> ${vc_ref:-?} on $remote/$ref"
if [ -z "${vc_ref:-}" ] || [ "${vc_ref:-0}" -le "${vc_base:-0}" ]; then
  {
    echo "::error:: Исходники изменились, а VERSION_CODE не поднят ($vc_base -> $vc_ref)."
    echo "Прогони bash buildScript/bump-version.sh и закоммить, либо --skip-version-gate (UI-only на redesign)."
  } >&2
  exit 1
fi
echo "VERSION_GATE: ok"
