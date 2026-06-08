#!/usr/bin/env python3
"""Distill husi_simple_log_*.txt exports into redacted field-log JSON fixtures."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from pathlib import Path

RE_UUID = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
    re.I,
)
RE_IP = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
RE_VLESS = re.compile(r"vless://\S+")
RE_HTTPS_SUB = re.compile(r"https://\S+/sub\S*")
RE_EMAIL = re.compile(r"\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b")
RE_TOKEN = re.compile(r"token=[^\s]+", re.I)
RE_H_TAG = re.compile(r"\bH\d+\b|SUB2-PARSE")
RE_PARSED = re.compile(r"SUB2-PARSE[^\n]*parsed=(\d+)", re.I)
RE_OWNERSHIP = re.compile(r"catalogOwnership=(\w+)", re.I)
RE_MIRROR = re.compile(r"H29 subscription_fetch_mirror")


@dataclass
class Scenario:
    id: str
    buildCode: int
    networkHints: list[str]
    hTagSequence: list[str]
    markers: dict[str, str]
    classifiedFailure: str
    linkedJourney: str


def redact(text: str) -> str:
    text = RE_VLESS.sub("vless://<redacted>", text)
    text = RE_HTTPS_SUB.sub("https://<redacted>/sub", text)
    text = RE_UUID.sub("<uuid>", text)
    text = RE_IP.sub("<ip>", text)
    text = RE_EMAIL.sub("<email>", text)
    text = RE_TOKEN.sub("token=<redacted>", text)
    return text


def classify(lines: list[str], markers: dict[str, str]) -> tuple[str, str]:
    body = "\n".join(lines)
    parsed = markers.get("SUB2-PARSE_parsed")
    if parsed == "0" or "parsed=0" in body:
        return "sub_add_no_proxies_after_fetch", "sub_add_import"
    if markers.get("catalogOwnership") == "USER" and "GH_MANAGED" in body:
        return "sub_ownership_drift", "sub_survives_bootstrap"
    if RE_MIRROR.search(body):
        if parsed == "0":
            return "sub_add_no_proxies_after_fetch", "sub_add_import"
        return "wl_fetch_mirror", "sub_add_import"
    if "H4" in body and "H24" in body and "subsWlMarked=0" in body:
        return "connect_pool_stuck", "connect_user_pool_priority"
    return "unknown", "sub_add_import"


def extract_build_code(lines: list[str]) -> int:
    for line in lines[:40]:
        m = re.search(r"buildCode=(\d+)", line)
        if m:
            return int(m.group(1))
        m = re.search(r"VERSION_CODE[=:]?\s*(\d+)", line)
        if m:
            return int(m.group(1))
    return 0


def distill_file(path: Path) -> Scenario | None:
    raw = path.read_text(encoding="utf-8", errors="replace")
    lines = [redact(line.rstrip()) for line in raw.splitlines() if "[SimpleMode]" in line]
    if not lines:
        return None

    h_tags: list[str] = []
    for line in lines:
        for tag in RE_H_TAG.findall(line):
            if tag not in h_tags:
                h_tags.append(tag)

    markers: dict[str, str] = {}
    for line in lines:
        m = RE_PARSED.search(line)
        if m:
            markers["SUB2-PARSE_parsed"] = m.group(1)
        m = RE_OWNERSHIP.search(line)
        if m:
            markers["catalogOwnership"] = m.group(1)

    network_hints: list[str] = []
    if any("whitelistOnly=true" in line for line in lines):
        network_hints.append("whitelistOnly=true")
    if RE_MIRROR.search("\n".join(lines)):
        network_hints.append("H29 subscription_fetch_mirror")

    failure, journey = classify(lines, markers)
    stamp = datetime.fromtimestamp(path.stat().st_mtime, tz=timezone.utc).strftime("%Y%m%d")
    scenario_id = f"{stamp}_{path.stem[:24]}"

    return Scenario(
        id=scenario_id,
        buildCode=extract_build_code(lines),
        networkHints=network_hints,
        hTagSequence=h_tags,
        markers=markers,
        classifiedFailure=failure,
        linkedJourney=journey,
    )


def default_input_dirs() -> list[Path]:
    home = Path.home()
    candidates = [
        home / "Downloads" / "Telegram Desktop",
        Path("field-logs/inbox"),
    ]
    return [p for p in candidates if p.is_dir()]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input",
        type=Path,
        action="append",
        default=[],
        help="Directory with husi_simple_log_*.txt (repeatable)",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("composeApp/src/commonTest/resources/field-log-scenarios"),
        help="Redacted JSON output directory",
    )
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)

    inputs = args.input or default_input_dirs()
    if not inputs:
        print("No input directories found (Telegram Downloads / field-logs/inbox)", file=sys.stderr)
        return 1

    logs: list[Path] = []
    for root in inputs:
        logs.extend(sorted(root.glob("husi_simple_log_*.txt")))

    if not logs:
        print(f"No husi_simple_log_*.txt under {inputs}", file=sys.stderr)
        return 1

    args.output.mkdir(parents=True, exist_ok=True)
    written = 0
    for log_path in logs:
        scenario = distill_file(log_path)
        if scenario is None:
            continue
        out_path = args.output / f"{scenario.id}.json"
        payload = asdict(scenario)
        if args.dry_run:
            print(json.dumps(payload, indent=2))
        else:
            out_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
            written += 1

    print(f"Processed {len(logs)} logs, wrote {written} fixtures to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
