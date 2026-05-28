# Scenario matrices

Program of layered CI gates for network uplink modes, handoff/reconnect policy, ruleset bootstrap (H36), and cross-subsystem glue. Each level has a dedicated workflow and a local Gradle mirror.

## Levels

| Level | Workflow | Local command | What it guards |
|-------|----------|---------------|----------------|
| **L0** | [stability-matrix.yml](../.github/workflows/stability-matrix.yml) | `./gradlew :composeApp:matrixTest` | Policy tables: network switch, pool degradation, recovery, in-scan flap, handoff, session health, subscription fetch |
| **L1** | [network-scenario-matrix.yml](../.github/workflows/network-scenario-matrix.yml) | `./gradlew :composeApp:networkScenarioTest` | Uplink snapshots (open / WL / no inet), handoff sequences, WL subscription fetch, H36 ruleset retry |
| **L2** | [integration-scenario-matrix.yml](../.github/workflows/integration-scenario-matrix.yml) | `./gradlew :composeApp:integrationScenarioTest` | Catalog sync invariants, route quick profile, coordinator glue |
| **L3** | (optional, v2) | manual / instrumented | Real BS uplink on device |

L1 does **not** replace [jdk-matrix-test.yml](../.github/workflows/jdk-matrix-test.yml) (full `desktopTest` × JDK). It only runs `fr.husi.scenario.network.*`.

## Network scenario axes (L1)

See [BS_CS_NETWORK.md](./BS_CS_NETWORK.md) for uplink vs tunnel probe targets.

| ID | Axis | Assert focus |
|----|------|----------------|
| `open_full` | Open internet | Health route uses open-appropriate URLs (no ya.ru) |
| `wl_bs` | WL uplink | Tunnel health: Telegram / BS hosts only |
| `no_inet` | Offline probe | `hasAnyInternet=false`, degraded autoselect |
| `wl_partial` | WL, no Google | BS health route; merged pool fallback steps |
| `handoff_wifi_lte` | wlan0 → rmnet | `REASON_CROSS_INTERFACE` |
| `handoff_carrier_restore` | Same iface restore | `REASON_CARRIER_RESTORE` |
| `handoff_link_rebound` | Same name, new ifindex | `REASON_LINK_REBOUND` |
| `flap_in_scan` | wifi↔lte×N | No compact reprobe on handoff |
| `wl_flip_open_to_wl` | WL mode change | `wl_to_open` full probe reason |
| `reconnect_handoff` | After connect handoff | `useCompactReprobe=false` |
| `ruleset_remote_fail_local_ok` | H36 | Second pass `preferLocalRuleSet=true` |
| `ruleset_partial_local_fallback` | Rule-set resolve | Partial local `geo/*.srs` keeps local hits and falls back to remote for missing tags |
| `sub_fetch_wl_direct` | WL, VPN off | Yandex mirror for GitHub raw |
| `sub_fetch_wl_vpn` | WL, VPN on | No mirror |

Implementation: `composeApp/src/commonTest/kotlin/fr/husi/scenario/network/`. Desktop injects probe state via `SimpleModeNetworkProbeHooks` when `husi.scenarioTest=true`.

## Path filters (CI)

**network-scenario-matrix** runs on changes to:

- `composeApp/**/simplemode/**`
- `composeApp/**/bg/**`
- `composeApp/**/database/AutoServer*`
- `composeApp/**/fmt/ConfigBuilder*`
- `composeApp/**/scenario/network/**`
- `docs/BS_CS_NETWORK.md`
- `docs/SCENARIO_MATRICES.md`
- `.github/workflows/network-scenario-matrix.yml`

**integration-scenario-matrix** additionally watches `subscription/**`, `scenario/integration/**`, and its workflow file.

## Before push

```bash
./gradlew :composeApp:networkScenarioTest
./gradlew :composeApp:matrixTest          # optional L0
./gradlew :composeApp:integrationScenarioTest  # when touching catalog/route glue
```

Target: L1 completes in under ~5 minutes on CI.

## Not in v1

- OS-level WL emulation on GitHub runners
- Full `desktopTest` in scenario workflows
- Replacement for manual BS uplink checks (release checklist / instrumented v2)
