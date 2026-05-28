#!/usr/bin/env python3
"""Prune duplicate GitHub Actions Gradle caches (transforms + dependencies)."""
from __future__ import annotations

import json
import subprocess
import sys


def prune(repo: str, prefix: str, keep: int) -> int:
    out = subprocess.check_output(
        [
            "gh",
            "cache",
            "list",
            "-R",
            repo,
            "-k",
            prefix,
            "--limit",
            "200",
            "--sort",
            "last_accessed_at",
            "--order",
            "desc",
            "--json",
            "id",
        ],
        text=True,
    )
    ids = [entry["id"] for entry in json.loads(out)][keep:]
    for cache_id in ids:
        subprocess.run(["gh", "cache", "delete", str(cache_id), "-R", repo], check=True)
    return len(ids)


def main() -> int:
    repo = sys.argv[1] if len(sys.argv) > 1 else "shvshnkr/dahusim"
    keep_transforms = int(sys.argv[2]) if len(sys.argv) > 2 else 5
    keep_dependencies = int(sys.argv[3]) if len(sys.argv) > 3 else 3
    deleted = prune(repo, "gradle-transforms", keep_transforms)
    print(f"Deleted {deleted} gradle-transforms cache(s) from {repo} (kept {keep_transforms}).")
    deleted = prune(repo, "gradle-dependencies", keep_dependencies)
    print(f"Deleted {deleted} gradle-dependencies cache(s) from {repo} (kept {keep_dependencies}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
