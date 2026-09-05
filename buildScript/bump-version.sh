#!/usr/bin/env bash
# bump-version.sh — VERSION_CODE++ в husi.properties (правило bump: AGENTS.md).
# Usage: bash buildScript/bump-version.sh [--set N]
set -euo pipefail

f="husi.properties"
[ -f "$f" ] || { echo "Нет $f — запускай из корня репо"; exit 1; }
cur="$(sed -n 's/^VERSION_CODE=//p' "$f" | tr -d '[:space:]')"
[ -n "$cur" ] || { echo "VERSION_CODE не найден в $f"; exit 1; }

if [ "${1:-}" = "--set" ]; then
  [ -n "${2:-}" ] || { echo "usage: bump-version.sh --set N"; exit 2; }
  next="$2"
else
  next=$((cur + 1))
fi

sed -i "s/^VERSION_CODE=.*/VERSION_CODE=${next}/" "$f"
echo "VERSION_CODE: ${cur} -> ${next}"
