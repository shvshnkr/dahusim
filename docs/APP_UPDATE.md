# In-app updates (GitHub channel)

## Channel URL

`https://github.com/shvshnkr/dahusim/releases/download/app-update-channel/app-update.json`

The app checks this manifest when **Settings → App updates → Check for app updates** is enabled (expert mode).

## Release flow

1. Push to `main` → **All platforms (auto pre-release)** builds rolling/desktop pre-releases.
2. Test artifacts manually.
3. **Actions → Promote app update channel**:
   - `run_id` — from successful all-platforms run (or empty = latest success).
   - `offer_update` — `false` freezes the channel without deleting rolling releases.
   - `publish` — `false` prints manifest preview only (dry-run).
4. Users on builds with matching Android signing cert receive the in-app prompt.

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
- Source tags default to `rolling-<run_id>`, `linux-desktop-linux-amd64-<run_id>`, `windows-desktop-windows-amd64-<run_id>`.
