# Subscription Catalog

`daHusiM` can sync subscription groups from a remote text catalog.

## Purpose

- Keep a centrally managed list of subscription links in GitHub.
- Add/update links without shipping a new app build.
- Prevent destructive mistakes with strict parsing and anti-wipe guards.
- Declare WL vs open autoselect pool membership per feed (`pool_role`).

## Ownership categories (disjoint)

| Category | `source_id` / marker | Catalog UPSERT/REMOVE | UI delete | WL autoselect |
|----------|----------------------|------------------------|-----------|---------------|
| **PROTECTED_BUILTIN** | `builtin.reserved` | never | blocked | only if user fills the slot |
| **BACKEND_MANAGED** | `gh.<source_id>` | yes | yes | via `pool_role` |
| **USER** | empty or non-`gh.`/`builtin.reserved` | never | yes | never (name heuristics ignored) |

Bootstrap seeds use `builtin.<source_id>` until the first matching catalog UPSERT promotes them to `gh.<source_id>`.

If a USER subscription shares a URL with a catalog UPSERT, the app creates a **new** `gh.*` group and leaves the USER group unchanged (log `H16 catalog_upsert_disjoint`).

## File format

Header and metadata:

```text
HUSI_SUBSCRIPTION_CATALOG_V1
generation=42
allow_empty=false
```

Records:

```text
UPSERT|source_id|display_name|https_url|subscription_type|fetch_profile|pool_role|optional_custom_user_agent
REMOVE|source_id
```

Supported values:

- `subscription_type`: `RAW`, `OOCv1`, `SIP008`
- `fetch_profile`: `default`, `happ`, `v2rayng`, `v2raytun`, `incy`, `custom`
- `pool_role` (optional, 7th field): `wl`, `open`, `any` (default `any` when omitted)

Examples:

```text
UPSERT|mifa-main|Mifa Main|https://mifa.world/vless|RAW|default|open
UPSERT|white-lattice|WhiteLattice|https://example.com/wl.txt|RAW|default|wl
UPSERT|paid-main|Paid Main|https://example.com/subscription|SIP008|happ
REMOVE|legacy-id
```

Custom User-Agent with pool:

```text
UPSERT|corp-panel|Corp panel|https://vpn.corp.example/sub|RAW|custom|wl|MyCorpVPN/1.0
```

Legacy 6-field lines (no `pool_role`) remain valid; `pool_role` defaults to `any`.

## Lifecycle (BACKEND_MANAGED only)

| Operation in txt | Effect |
|------------------|--------|
| `UPSERT\|id\|...\|wl` | Create/update `gh.id`, set `connectPoolRole=WL` |
| `REMOVE\|id` | Staged removal |
| No UPSERT for `id` | Staged removal for existing `gh.id` |
| Change `wl` → `open` | UPSERT with new `pool_role` (no REMOVE required) |

Deletions are staged: first missing generation marks pending removal; actual deletion on a later sync or after a 24h grace period. Safety guards block wiping all gh-managed groups or oversized bulk deletes.

PROTECTED_BUILTIN and USER groups are never updated or removed by the catalog.

## Fetch profile notes

- `default`: Dahusim User-Agent (`husi/…` from BuildConfig).
- `happ`, `v2rayng`, `v2raytun`, `incy`: client preset; version comes from Quick settings templates (editable, not tied to app release).
- `custom`: full User-Agent string from the record (supports `$version`, `$version_code`, `$box_version`).

## WL pool

`pool_role` on gh-managed feeds is stored as `SubscriptionBean.connectPoolRole` and drives `WlSubscriptionTag` / `ConnectPoolPolicy` WL autoselect. See [wl.md](./wl.md).

## Static feeds (`docs/subscription-feeds/`)

`tri_228-open.txt` and `tri_228-wl.txt` are split from upstream `tri_228.txt` (open: `#для wifi и моб инет без бс` … `#для обхода бс`; wl: BS-bypass section and below). When upstream changes, rebuild both files manually and bump catalog `generation` if URLs or `source_id` change.

## Degraded feed / jail

Auto-update tracks **group-level** health in Room (`subscription_update_states`), separate from per-profile probe jail:

| State | Meaning | Connect refresh | Background WorkManager |
|-------|---------|-----------------|------------------------|
| **OK** | Last fetch succeeded | Normal due queue | Normal due |
| **SUSPECT** | 1–2 transient failures | At most **one** retry per connect budget | Due with ~90 s backoff (`nextAttemptAt`) |
| **JAIL** | Permanent (404/410) or repeated failures | **Skipped** (`H39 sub_refresh_skipped_jail`) | **No notification**; one unjail attempt per cycle after ~2 h |

Jail **does not delete** profiles in the group — it only defers HTTP fetch. Logs: `H39 sub_update_state`, `H39 sub_jail`, `H39 sub_unjail`.

### Subscription update jail (unjail rules)

- **JAIL clears** only via the background WorkManager worker (`pickUnjailGroupId`, at most one feed per cycle after backoff) or a **manual** group update in the UI.
- **Connect refresh** and **foreground fallback** refresh (`BaseService` tunnel degrade) use `connectRefresh=true`: they skip JAIL feeds and cap SUSPECT to one; they **never** unjail.
- **Permanent HTTP** (404/410) moves the group to JAIL on the first classified permanent failure.

## Operations

- Expert settings expose:
  - catalog URL;
  - sync interval (6..12 hours);
  - manual “Sync catalog now”.
- Background sync runs periodically with existing subscription schedulers.
- After a successful catalog apply that creates or updates groups, the app runs a targeted `GroupUpdater` pass on affected groups.
