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

## Android signing

OTA install requires the **same signing key** as the installed APK.

- CI uses `assemblePlayReleaseAllAbi` when GitHub secrets `KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS`, and `KEYSTORE_BASE64` are set.
- Without secrets, CI publishes **playDebug** APKs (test channel only; signature will not match a release-signed install).

## Promote defaults

- `min_version_code` empty → **0** (offer to all installed versions below `versionCode`).
- Source tags default to `rolling-<run_id>`, `linux-desktop-linux-amd64-<run_id>`, `windows-desktop-windows-amd64-<run_id>`.
