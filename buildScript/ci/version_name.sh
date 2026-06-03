#!/usr/bin/env bash
# Print effective VERSION_NAME (base + optional VERSION_NAME_SUFFIX from husi.properties).
set -euo pipefail

METADATA="${1:-husi.properties}"
# UTF-8 BOM on line 1 breaks awk key match (same as Gradle requireMetadata strip).
meta_lines="$(sed '1s/^\xEF\xBB\xBF//' "$METADATA")"
base="$(awk -F= '$1=="VERSION_NAME"{print $2; exit}' <<<"$meta_lines")"
suffix="$(awk -F= '$1=="VERSION_NAME_SUFFIX"{print $2; exit}' <<<"$meta_lines")"
if [ -z "$base" ]; then
  echo "Missing VERSION_NAME in $METADATA" >&2
  exit 1
fi
if [ -n "${suffix:-}" ]; then
  printf '%s-%s\n' "$base" "$suffix"
else
  printf '%s\n' "$base"
fi
