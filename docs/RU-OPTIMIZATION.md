# RU Optimization profile

This branch ships a small, focused set of defaults for users in Russia and nearby
blocked-by-DPI regions (RU/BY/KZ). Nothing here changes the protocol stack — these are
route, asset and inbound defaults tuned for the hostile-network reality.

## What changes

1. **Default route rules (RU locale).** On first launch on a device with locale
   `RU`, `BY` or `KZ`, the bundled default route preset produces
   `geosite-ru` / `geoip-ru` **bypass** rules (direct, not via proxy), plus the usual
   LAN / QUIC / ADS block rules. The rule-set tags are standard and ship with every
   supported provider (SagerNet, Loyalsoldier, Chocolate4U) — no extra downloads.

2. **Per-app bypass preset.** The app manager now has a **"Scan Russian apps"**
   action next to the existing "Scan China apps" one. It selects common RU apps
   (Sber, Tinkoff, VTB, Alfa, Raiffeisen, Gosuslugi, Nalog, Pochta, VK, OK, Yandex\*,
   Mail.ru, Ozon, WB, Avito, Kinopoisk, Kaspersky, MTS/Beeline/Megafon/Tele2, etc.)
   so that Russian banking/government/marketplace apps go direct while everything else
   is proxied. Pattern matching is package-prefix based, mirroring the China scanner.

3. **Explicit "Apply Russia preset" action.** In addition to the locale-based
   first-launch seeding, the Route screen overflow menu now has an **"Apply Russia
   preset"** entry (and a symmetric "Apply China preset"). It is idempotent: it wipes
   the current rule set and re-seeds the RU defaults (sniff, hijack-dns, bypass-icmp,
   block-quic, block-ads, geosite-ru / geoip-ru bypass, bypass-lan). Use this after a
   fresh install on a non-RU locale or any time you want to reset to the RU template.

4. **Inbound credential hardening (VLESS-SOCKS5 mitigation).** The local SOCKS5/HTTP
   mixed inbound is never brought up without authentication:
    * `DataStore.ensureInboundCredentials()` generates a random UUID password on demand.
    * `Settings → Inbound → Allow access` now calls it automatically when toggled on.
    * `ConfigBuilder` calls it right before assembling the config for a real service run.
    * Empty `inbound username` shows an advisory summary in settings.

   Reference: https://publish.obsidian.md/zapret/VLESS-SOCKS5-vulnerability

## Why not ship a new RU rule-set provider

The three existing providers (SagerNet, Loyalsoldier, Chocolate4U) already distribute
`geoip-ru.srs` and `geosite-ru.srs`. Adding a dedicated RU provider would need a
maintained upstream in the `sing-box` SRS format, and every user would pay the cost of
another download. Users who want RKN-unblock lists or antifilter-style sources can
already switch the provider to **Custom** and paste the archive URL (see Route settings →
Rule Assets Provider → Custom).

## Compatibility

* The RU default rule preset is additive and is only applied on **first launch** —
  existing installs with rules already configured are not touched.
* The app scanner preset is a new menu action; it does not change behavior for users
  who don't invoke it.
* The credential auto-generation is idempotent and only runs when `inboundUsername` or
  `inboundPassword` is blank.

## Open follow-ups (not in this branch)

* TLS fragment / domain-fronting defaults are outbound-configured per-profile and are
  better left to user choice; we only ship the security advisory.
* A dedicated "Fake-IP for non-RU only" toggle is not needed since the generated
  bypass rules already direct RU traffic to the system resolver before fake-IP
  handling kicks in.
* Blocking RU trackers/ads via a dedicated list (EasyList Russia) is left to the user
  via Route settings → Rules → add `geosite-category-ads-all` + custom entries.
