#!/usr/bin/env bash
# Prune stale GitHub Actions Gradle caches for dahusim (repo default: github remote).
# Usage: prune_gha_gradle_caches.sh [repo] [keep_transforms] [keep_dependencies]
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$SCRIPT_DIR/prune_gha_gradle_caches.py" "$@"
