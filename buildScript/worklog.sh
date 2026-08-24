#!/usr/bin/env bash
# Append a session entry to AI/cursorworklog.md safely.
#
# Why: agents previously edited the worklog with full-file `write` and wiped
# history. This script is the ONLY allowed write path: it snapshots the current
# file, appends the block, and verifies the file only grew.
#
# Usage:
#   bash buildScript/worklog.sh <block-file>      # append block-file (UTF-8)
#   bash buildScript/worklog.sh <block-file> --keep-backup
#
# Rules:
# - Always keeps a timestamped backup in AI/backups/ (and a rolling .bak).
# - Never rewrites the file; only appends.
# - Exits non-zero and rolls back if the file would shrink.

set -euo pipefail

WORKLOG="AI/cursorworklog.md"
BACKUP_DIR="AI/backups"
ROLLING_BACKUP="AI/cursorworklog.md.bak"

if [ $# -lt 1 ]; then
    echo "usage: bash buildScript/worklog.sh <block-file> [--keep-backup]" >&2
    exit 1
fi
BLOCK_FILE="$1"
KEEP_BACKUP="${2:-}"

if [ ! -f "$WORKLOG" ]; then
    echo "error: $WORKLOG not found (run from repo root)" >&2
    exit 1
fi
if [ ! -f "$BLOCK_FILE" ]; then
    echo "error: block file '$BLOCK_FILE' not found" >&2
    exit 1
fi
if [ ! -s "$BLOCK_FILE" ]; then
    echo "error: block file '$BLOCK_FILE' is empty" >&2
    exit 1
fi

BEFORE_LINES=$(wc -l < "$WORKLOG")

# snapshot (timestamped + rolling)
mkdir -p "$BACKUP_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)
cp "$WORKLOG" "$BACKUP_DIR/cursorworklog-$STAMP.md"
cp "$WORKLOG" "$ROLLING_BACKUP"

# append
{
    printf '\n'
    cat "$BLOCK_FILE"
} >> "$WORKLOG"

AFTER_LINES=$(wc -l < "$WORKLOG")
if [ "$AFTER_LINES" -le "$BEFORE_LINES" ]; then
    echo "error: append failed (file did not grow), rolling back" >&2
    cp "$ROLLING_BACKUP" "$WORKLOG"
    exit 1
fi

ADDED=$((AFTER_LINES - BEFORE_LINES))
echo "worklog: appended $ADDED lines ($BEFORE_LINES -> $AFTER_LINES)"
echo "backup:  $BACKUP_DIR/cursorworklog-$STAMP.md"
if [ "$KEEP_BACKUP" = "--keep-backup" ]; then
    echo "rolling:  $ROLLING_BACKUP"
fi
