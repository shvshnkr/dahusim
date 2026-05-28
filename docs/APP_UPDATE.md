# In-app updates (GitHub channel)

## Channel URL

`https://github.com/shvshnkr/dahusim/releases/download/app-update-channel/app-update.json`

The app checks this manifest when **Settings → App updates → Check for app updates** is enabled (expert mode).

## Release flow

1. Update [`docs/changelog/CHANGELOG.ru.md`](../docs/changelog/CHANGELOG.ru.md) section **`[Unreleased]`** (user-facing RU; no `VERSION_NAME` bump required for text to appear).
2. Push to `main` → **All platforms (auto pre-release)** builds rolling/desktop pre-releases (body includes **Что нового** from `[Unreleased]` or git since last promote).
3. Test artifacts manually.
4. **Promote app update channel** (UI or CLI below):
   - `run_id` — from successful all-platforms run (or empty = latest success).
   - `use_changelog` — default **true**: same merge as pre-release → `app-update.json` field `notes`.
   - `offer_update` — `false` freezes the channel without deleting rolling releases.
   - `publish` — `false` prints manifest preview only (dry-run).
5. After promote, optionally run `changelog seal` (local) to archive `[Unreleased]` into **История promote**.
6. Users on builds with matching Android signing cert receive the in-app prompt.

## Changelog (RU)

- **Working section:** `## [Unreleased]` in [`docs/changelog/CHANGELOG.ru.md`](../docs/changelog/CHANGELOG.ru.md).
- **Promote boundary:** `sourceCommit` / `sourceRunId` in published `app-update.json` (clients ignore; CI uses for `git log` fallback).
- If `[Unreleased]` is empty, notes fall back to **commits since last channel `sourceCommit`** (subjects as bullets, RU heading).

## Local CLI (agent / maintainer)

From repo root (Git Bash on Windows: `bash buildScript/ci/gh-workflow.sh …` or `.\buildScript\ci\gh-workflow.ps1 …`):

| Command | Purpose |
|---------|---------|
| `gh-workflow.sh build [--wait]` | Trigger **All platforms** |
| `gh-workflow.sh status [--run-id]` | Inspect runs |
| `gh-workflow.sh promote [--run-id] [--dry-run] [--from-changelog]` | **Promote app update channel** |
| `gh-workflow.sh changelog verify` | OK if `[Unreleased]` has bullets or new commits since promote |
| `gh-workflow.sh changelog draft` / `draft-en` | Git range since last promote (RU / EN subjects) |
| `gh-workflow.sh changelog refresh` | Insert git draft under `[Unreleased]` |
| `gh-workflow.sh changelog seal [--run-id]` | Move `[Unreleased]` → **История promote** (local commit) |

Requires `gh` auth to `github` remote (`shvshnkr/dahusim`).

## Desktop (Linux/Windows) behavior

- Desktop update installs are **manual by design**:
  - the app downloads the selected artifact, verifies SHA-256 (and size when present), then opens it.
  - result is treated as pending user action (not immediate success).
- In Settings, desktop users can use **Open downloaded installer again** to reopen the last downloaded
  file/folder if the prompt was closed.
- Windows:
  - `installer` artifacts are opened directly.
  - `zip` / `jar` open the download folder so users can extract/run manually.
- Linux:
  - preferred order is `deb` -> `rpm` -> `pkgTarZst` -> `zip` -> `jar`.
  - desktop integration opens artifact (or folder fallback), final install is user-driven.

## Android signing

OTA install requires the **same signing key** as the installed APK.

- CI uses `assemblePlayReleaseAllAbi` when GitHub secrets `KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS`, and `KEYSTORE_BASE64` are set.
- Without secrets, CI publishes **playDebug** APKs (test channel only; signature will not match a release-signed install).

## Promote defaults

- `min_version_code` empty → **0** (offer to all installed versions below `versionCode`).
- Source tags default to `android-<run_id>`, `linux-desktop-linux-amd64-<run_id>`, `windows-desktop-windows-amd64-<run_id>`.
  Promote falls back to legacy `rolling-<run_id>` when the Android tag from an older CI run is still named that way.

## User expectations

- **Check ≠ install.** The app only downloads and prompts; there is no silent OTA install on Android.
- **Check ≠ newer build in git.** Users see an offer only after **Promote app update channel** publishes
  `app-update.json` with `offer_update` and a `versionCode` above the installed app.
- Auto-check (WorkManager / desktop loop / cold start) uses the same coordinator as **Check now**;
  a passing manual check does not prove background scheduling works.

## Timeline (ops)

1. Push to `main` → CI builds pre-release artifacts.
2. Test artifacts manually.
3. **Promote app update channel** → channel JSON visible to clients.
4. Clients with auto-check enabled: WorkManager (~24h) and/or foreground `onStart` may fetch the manifest.

## On-device debugging

- **Settings → Updates** (expert): last check time, auto interval, manual **Check now**.
- Logs: `H36` / `simpleModeLog` tag `SimpleMode` with `app_update_*`.
- DataStore: `appUpdateLastCheckAt` (seconds) — advanced only after a **successful** manifest fetch, not on network error.
