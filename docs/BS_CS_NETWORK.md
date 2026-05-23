# BS / CS / WL — памятка для агентов и разработчиков

См. также [Habr: белые списки](https://habr.com/ru/articles/1027276/) и скан [openlibrecommunity/twl](https://github.com/openlibrecommunity/twl).

## Термины

| Термин | Сеть | Что доступно **напрямую** (без VPN) | Зачем VPN |
|--------|------|-------------------------------------|-----------|
| **Открытая (open)** | Обычный мобильный/Wi‑Fi в РФ | Почти всё, в т.ч. Google | Обход ЧС, приватность |
| **WL / БС** (whitelist, «белый список») | Drop-all + L3 whitelist IP + L7 SNI | **Рунет**: ya.ru, dzen.ru, VK, банки, CDN из списка | Доступ к **зарубежным БС** (Telegram, Instagram, …) — IP не в whitelist |
| **ЧС** (цензура, не WL) | Обычная РФ без WL | **google.com**, apple.com часто OK | **youtube.com** и отдельные домены — DPI/блок, не то же самое что WL |

**БС** (в контексте simple mode / restricted): ресурсы, чей трафик **не проходит** с uplink WL (зарубежные IP / не-whitelist SNI). Пользователь включает VPN, чтобы открыть их **через туннель**.

**ЧС**: частичная цензура на **нормальном** интернете (Google жив, YouTube нет). Путать с WL нельзя: на WL Google по L3 мёртв, ya/dzen — живы.

## Ошибки при проектировании probe

1. **Проверять туннель через ya.ru / dzen.ru на WL** — они уже доступны с uplink; тест не измеряет VPN, только лишний dial к прокси.
2. **Проверять туннель через gstatic / cloudflare / generate_204 на WL** — это не БС для пользователя WL; плюс exit в PL/NL/US может резать или вести себя иначе, ложные «мёртвые» сервера.
3. **Считать успех direct probe на ya при prepare на WL** доказательством рабочего узла для Telegram — prepare должен бить в **БС-цель через профиль** (например `web.telegram.org`), либо TCP + политика без ложного post-connect.
4. **Uplink reachability** (google / dzen / ya / whitelist.txt) — только **direct**, `NetworkReachabilityProbe`, без sing-box outbound.

## Куда какие URL (код)

| Фаза | Open net | WL (БС uplink) |
|------|----------|----------------|
| Uplink / `whitelistOnly` | google 204 | dzen, ya, whitelist sources — **direct only** |
| Prepare URL (direct sing-box per profile) | gstatic + user test URL | **БС через профиль** (`tunnelBsProbeUrls`) |
| Post-connect / session tunnel | gstatic + user URL | **БС через live tunnel** (`web.telegram.org`); skip only via `simpleModeWlSkipTunnelHealthCheck` (default **off**) |

## Файлы

- `SimpleModeHealthRoute.kt` — единая матрица URL и skip-политика
- `NetworkReachabilityProbe.kt` — только uplink, не туннель
- `BaseService.kt` / `SimpleModeSessionHealth.kt` — post-connect и periodic tunnel health

При сомнении: на WL **не добавляй** в tunnel health домены, которые и так открыты с телефона без VPN.
