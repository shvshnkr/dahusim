# Subscription Catalog

`daHusiM` can sync subscription groups from a remote text catalog.

## Purpose

- Keep a centrally managed list of subscription links in GitHub.
- Add/update links without shipping a new app build.
- Prevent destructive mistakes with strict parsing and anti-wipe guards.

## File format

Header and metadata:

```text
HUSI_SUBSCRIPTION_CATALOG_V1
generation=42
allow_empty=false
```

Records:

```text
UPSERT|source_id|display_name|https_url|subscription_type|fetch_profile|optional_custom_user_agent
REMOVE|source_id
```

Supported values:

- `subscription_type`: `RAW`, `OOCv1`, `SIP008`
- `fetch_profile`: `default`, `happ`, `custom`

Examples:

```text
UPSERT|mifa-main|Mifa Main|https://mifa.world/vless|RAW|default
UPSERT|paid-main|Paid Main|https://example.com/subscription|SIP008|happ
REMOVE|legacy-id
```

## Safety behavior

- Catalog must have the exact header `HUSI_SUBSCRIPTION_CATALOG_V1`.
- `generation` is required and must increase monotonically.
- Duplicate `source_id` records are rejected.
- Duplicate `UPSERT` links in the same catalog are rejected.
- Only `https://` links are accepted for `UPSERT`.
- App blocks dangerous diffs:
  - catalog has zero UPSERT while remote-managed items exist;
  - diff would remove all remote-managed subscriptions;
  - bulk delete exceeds safety thresholds.
- Deletions are staged:
  - first missing generation marks item as pending removal;
  - actual deletion happens on a later successful sync or after grace period.
- Only GitHub-catalog-managed subscriptions (`source_id` stored with internal `gh.` prefix)
  can be auto-removed.
- Built-in subscriptions are initially marked with internal `builtin.` source ids.
  On first matching `UPSERT` by link, ownership is promoted to `gh.<source_id>` so later updates
  are tracked by stable `source_id` (not by link matching).
- If catalog adds an already existing link, app updates the existing group instead of creating
  a duplicate.

## Fetch profile notes

- `default`: uses app default User-Agent.
- `happ`: uses `happ/2.9.0`.
- `custom`: uses the custom User-Agent string from the record/user settings.

## Operations

- Expert settings expose:
  - catalog URL;
  - sync interval (6..12 hours);
  - manual “Sync catalog now”.
- Background sync runs periodically with existing subscription schedulers.

