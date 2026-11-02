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
UPSERT|open-feed|Open Feed|https://example.com/open.txt|RAW|default|open
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

`tri_228-open.txt` and `tri_228-wl.txt` were split from upstream `tri_228.txt` (open: `#для wifi и моб инет без бс` … `#для обхода бс`; wl: BS-bypass section and below). **Removed in generation 15**: every node in both feeds failed real-traffic liveness checks (telegram/youtube through tunnel) as of 2026-11-02; the files stay in the repo for history only.

`wl-standalone.txt` was a curated WL feed for single-node relays. **Removed in generation 15**: the upstream feed no longer carries any usable node (only a commented-out domain-only SOCKS relay that blocks IPv4 CONNECT to Telegram DCs).

`zieng2-wl` points at the upstream BS-direct mirrors; the old `hub.mos.ru` mirror returns 404 (upstream restructure). Generation 15 canonical link is `gitverse.ru` (RU-hosted, reachable under whitelist restrictions), raced against `raw.githubusercontent.com`, `bitbucket.org` and `codeberg.org` mirrors of `vless_universal.txt` / `list_universal.txt`. The codeberg mirror carries the freshest node set (includes VK-hosted entries); the other three are byte-identical. The list refreshes hourly upstream and profiles churn daily; transient 404s are handled by the subscription jail (profiles stay cached, one unjail attempt per cycle). Liveness 2026-11-02: 1/10 sampled nodes pass real-traffic checks (Selectel xhttp node); VK-hosted xhttp nodes accept TCP but never answer from a RU BL uplink.

`vlessfo-open` (Vlessforu) points at the upstream self-hosted list; 5/10 sampled nodes pass real-traffic checks (2026-11-02).

`razlo4ka-open` (Razlo4ka) was removed in generation 15: 0/22 sampled nodes alive across two independent samples.

`migiti-wl` and `migiti-fast-ru` were removed: the upstream repo `misha12333211-ctrl/proxy-subs` returns 404 for all list files.

`aetris-blacklist` (AetrisVPN BlackList, open; 4/10 alive) and `aetris-wl-lite` (AetrisVPN WhiteList Lite, WL; 5/10 alive, all live nodes are shadowsocks) are the ЧС/БС-lite variants from the same author as the removed `aetris-vpn` (flaafix), auto-updated upstream. `aetris-vpn` itself was removed in generation 15 (0/22 alive).

`kizyak-beta7` (КIЗЯК VPN 7, WL; 7/9 alive — the strongest feed of the November 2026 sweep) replaces `kizyak-beta6` (catalog generation 15; upstream renamed the file). `kizyak-beta6-bl` (КIЗЯК VPN BLACKLIST, WL; 2/2 alive) carries `[BL]`-tagged nodes. `kizyak-testru` was removed in generation 15 (0/22 alive; nearly all hysteria2 entries have dead DNS).

## Generation 15 cleanup (2026-11-02)

Real-traffic liveness sweep (353 node tests via sing-box + SOCKS, RU BL uplink; a node counts alive only if exit IP differs from the tester AND api.telegram.org / youtube `generate_204` answer through the tunnel) removed every feed with zero live nodes: `swordware-main`, `black-vless-rus-mobile`, `vless-wl-rus-mobile`, `aetris-vpn`, `tri-228-open`, `tri-228-wl`, `wl-standalone`, `white-lattice`, `white-list-vpn-black`, `migiti-wl`, `razlo4ka-open`, `kizyak-testru`. Starter seeds that were never catalog-managed (etoneya, CYBERPORTAL, goida, RKP, hiztin, haven, go-engine, terik21, kafka, mifa, jsxta, hardvpn, migiti-fast-ru) were dropped from `SubscriptionCatalogDefaults.STARTER_SEEDS`; existing installs delete the stale builtin groups on the next bootstrap (`DefaultUserBootstrap.retireRemovedStarterSeeds`). Nullcore `v2whitelist` is an APK landing page without a subscription feed — not a source (re-confirms the generation 12 decision).

Known app-level gap found by the sweep (fixed 2026-11-02): xray share-links with camelCase `extra` JSON (xhttp transport) were merged verbatim into the sing-box transport config, but sing-box expects snake_case and a positive `x_padding_bytes` — such nodes failed config validation (`x_padding_bytes cannot be disabled`). `buildSingBoxOutboundXHTTPTransport` now normalizes extra keys camelCase→snake_case, drops empty/zero/false values (incl. `downloadSettings`/`extra` shells and zero xmux entries) and injects the xray-default `x_padding_bytes: "100-1000"` when absent. Regression: `V2RayFmtTest` (camelCase zieng2-style extra → snake_case transport).

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
