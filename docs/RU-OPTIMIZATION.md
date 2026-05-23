# RU Optimization profile

Defaults for Russia and nearby (RU/BY/KZ): routing, assets, and inbound hardening. No protocol stack changes.

## Source of truth

This profile is **maintained only in this project** (default branch `main` in this repository). There is no separate upstream line that “owns” it anymore: the old upstream branch for this work is dead, and **the canonical behaviour is whatever you ship here**.

## What changes

1. **Default route rules (RU locale).** First launch with locale `RU`, `BY`, or `KZ` seeds
   **geosite-ru** / **geoip-ru** direct bypass, plus sniff, hijack-dns, ICMP bypass, QUIC/ads
   rejects, and **private + LAN** (`CONTENT_PRIVATE`) direct. No separate NSPK domain list —
   those hosts are covered by RU geosite.

   In sing-box terms, **geosite-ru** is the domain/rule list tagged `ru` in the community
   **geosite** database (not “all .ru TLD” literally, though overlap is large). **geoip-ru** is
   the **GeoIP** country database’s **RU** segment for IP CIDRs. With a built-in rule-set
   provider (Official / Loyalsoldier / Chocolate4U), the app wires these as **remote** `.srs`
   from GitHub so they work even if **Route → rule assets** was never downloaded into `geo/`.
   **Custom** route provider only changes how **Route → Update** downloads bundles; the live
   sing-box config still loads standard `geosite-*` / `geoip-*` tags from **SagerNet** raw URLs
   so presets work without a filled `geo/` folder.

2. **Per-app bypass — "Scan Russian apps".** Same idea as the China scanner: package-name
   prefixes **and** manifest/DEX checks for RU-related class names (vendor/SDK-style
   heuristics).

3. **"Apply Russia preset" / "Apply China preset".** If the rule table is **empty**, full
   seed as above. If rules already exist, **merge** only missing geosite/geoip (and CN Play
   Store rule for China) — user rules are not wiped.

4. **Inbound credentials.** `DataStore.ensureInboundCredentials()` sets random **username**
   and **password** (`InboundCredentialRandom`, platform `SecureRandom`) when either field is
   blank. `ConfigBuilder` calls it before building a live config. Settings → Inbound still
   shows the advisory when credentials are unset.

   Reference: https://publish.obsidian.md/zapret/VLESS-SOCKS5-vulnerability

## Providers

Existing rule providers already ship `geosite-ru` / `geoip-ru`. For custom lists use
**Custom** in Route → Rule Assets Provider.

## Simple mode (whitelist networks)

When Google is unreachable but Dzen or the built-in HTTP probe URLs respond, the app treats the
network as **whitelist-only** for simple mode: auto server selection can use **four built-in
Trojan helper profiles** (public community endpoints; same pool logic as other profiles when
Google is OK includes a fifth helper with no special priority). Update endpoints in code if they
go offline.

Фоновое обновление подписок через WorkManager **не** запускает тяжёлый `SubscriptionAutoUpdate`,
если VPN ещё не подключён и проверка `NetworkReachability` не видит «живого» интернета
(Google / Dzen / ya.ru / whitelist-зонды). Когда VPN уже в состоянии Connected, обновления
как раньше выполняются через туннель.

## Simple-mode diagnostics

Rolling log: app cache `simple-mode/simple_mode_app.log` (share via **Send logs** in simple mode).
Grep anchors: `H24` (pool), `H38` (WL cap), `H14`/`H17` (probes), `H4`/`H1` (fallback), `H37` (health).

## Compatibility

* First-launch RU seed only when the rule list is empty and `rulesFirstCreate` allows it.
* Scanner is opt-in from the app manager.
* Credential generation is idempotent when fields are blank.
