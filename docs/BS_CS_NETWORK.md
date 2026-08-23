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
3. **Считать успех direct probe на ya при prepare на WL** доказательством рабочего узла для Telegram — prepare должен бить в **БС-цель через профиль** (composite: `web.telegram.org` **и** HTTP к DC IP `91.105.192.100`), либо TCP + политика без ложного post-connect.
4. **Считать web.telegram.org достаточным для native Telegram** — domain-only SOCKS и частичный egress VPS (Timeweb без WARP) могут пройти web, но не MTProto к DC IP; нужен второй шаг probe.
5. **Uplink reachability** (google / dzen / ya / whitelist.txt) — только **direct**, `NetworkReachabilityProbe`, без sing-box outbound.

## Куда какие URL (код)

| Фаза | Open net | WL (БС uplink) |
|------|----------|----------------|
| Uplink / `whitelistOnly` | google 204 | dzen, ya, whitelist sources — **direct only** |
| Prepare URL (direct sing-box per profile) | **OPEN + «Проверка Telegram» (default):** `web.telegram.org` + **DC IP** `http://91.105.192.100/` (composite messenger ready); OFF: gstatic + user test URL; CONFIRM: telegram + gstatic + cloudflare | **БС PRIMARY:** composite messenger (web + 91.105); **CONFIRM** (tie / tcp-alive / wave-2): + instagram + facebook |
| Post-connect / session tunnel | **OPEN + флаг:** composite messenger (web + 91.105); OFF: gstatic + user URL (+ cloudflare on CONFIRM) | **БС PRIMARY:** composite messenger; **CONFIRM** при inconclusive: + instagram + facebook |
| Prepare batch (WL, N>1) | — | sing-box **urltest group** (`PrepareGroupUrlProbe`), fallback per-profile |

## Файлы

- `SimpleModeHealthRoute.kt` — единая матрица URL и skip-политика; `DataStore.simpleModeTelegramProbe` (default ON) ослабляет только **OPEN**
- `SimpleModeMessengerProbe.kt` — composite messenger: web (domain/L7) + DC IP `91.105.192.100` (native egress); `149.154.167.51` — tie-break, не gate
- `NetworkReachabilityProbe.kt` — только uplink, не туннель
- `BaseService.kt` / `SimpleModeSessionHealth.kt` — post-connect и periodic tunnel health

При сомнении: на WL **не добавляй** в tunnel health домены, которые и так открыты с телефона без VPN.

## Полевой BS-тест (чеклист для агентов)

BS-режим доступен не всегда: на мобильном интернете Google может быть жив (`H37 google=true wlOnly=false`) —
это **OPEN**, не BS. БС-валидация фич autoselect требует `google=false wlOnly=true`.

**BS не привязан ко времени суток.** Белые списки включают в любой момент, днём и ночью (например, при атаках
дронов на регион) — «ночного BS-окна» нет. Ориентир — только телеметрия `H37 google=false wlOnly=true`, а не часы;
0-url-ok окна случаются на BS-флапах подписок вне зависимости от времени.

Рецепт (телефон VBC0223426003938, простой режим):
1. `adb install -r androidApp/build/outputs/apk/play/debug/husi-1.2.0-alpha.39-redesign-play-arm64-v8a-debug.apk` → `adb shell dumpsys package fr.husi.debug | grep versionCode` (должен совпасть с тестируемым VERSION_CODE).
2. `adb shell svc wifi disable` → `adb shell "dumpsys wifi"` на `WifiState 0` (EnabledState→DisabledState). Активный uplink — мобильный (rmnet*).
3. Запуск: `adb shell am force-stop fr.husi.debug` → `adb shell am start -n fr.husi.debug/fr.husi.ui.MainActivity` (ИМЕННО `fr.husi.ui.MainActivity`, не `fr.husi.MainActivity`).
4. Тап Подключить: `adb shell "input tap 610 1769"`. При «Waiting for servers…» НЕ закрывать — ждать `H21 server_revival_watch attempt=N` (BS: `google=false wlOnly=true`).
5. Проверка контекста на поле: `H37 reachability_route … google=false … wlOnly=true` (иначе не BS, смотри §Термины).
6. Забрать лог: `adb shell "run-as fr.husi.debug cat cache/simple-mode/simple_mode_app.log"` → `$TEMP/bs_session<N>_<CODE>.log`. Метрика — по `H21 preconnect_done elapsedMs=… result=Success|AllProbesDead`.
7. Вернуть: `adb shell svc wifi enable` → `WifiState 1` (EnabledState).
8. Worklog append: поля `maps_updated`/`map_sync`; лог сохранить.

### BS-чеклист по кодам (грейдинг по «времени от тапа до живого туннеля»)

| Код | Ожидание | Референс |
|-----|----------|----------|
| 751 (revival watch) | H22 dead-end → H21 `server_revival_watch` attempt=1..N, exhausted — без мёртвого коннекта | 406s ночь → мёртвого коннекта нет (валидировано 20.08) |
| 752 (MERGED-all + sweep) | H4 `wl_pool_merged_from_start` → H24 `poolMode=MERGED pool≈2331` → H14 `tcp_probe_round` batch=128 | preconnect 57с (валидировано 20.08) |
| 753 (early-connect + pre-cache) | H4 `early_connect` при 1-м url-ok (без ranking), pre-cache DataStore-читаний | preconnect 75,5с при 0-url-ok 6 раундов (валидировано 20.08) |
| 754 (pipelining + LKG pre-seed + adaptive TCP) | Даже в 0-url-ok окне: H14 `timeoutMs=800/1200` + H17 `mode=wl_progressive_round` (URL N ∥ TCP N+1); при свежих LKG — `H17 mode=wl_lkg_preseed` → коннект | цель: <40с в 0-url-ok окне; <~25с при свежих LKG; 753 было 75,5с. **НЕ валидирован** (на поле в тот период BS-режима не было; BS бывает в любой момент — не откладывать валидацию только на ночь) |
| 760 (dead-end без quick probe + bound synthetic WL) | 0-url-ok sweep (в т.ч. `shouldQuickProbe=false` sequential 36) → H22 `prepare_wl_no_url_ok` → H21 `server_revival_watch`; фейковый Connected на мёртвом туннеле → H34 `session_health_synthetic_limit` через ≤3 synthetic-цикла → H30 session_recover | поле 21.08 (код 758): 72460 (сервер на GitHub IP, L3-блок на BS) держал «Connected» 75с+ с `inconclusive=underlying_proxy_dial_only` + `synthetic=true`; фолбэк не шёл. Проверить: после dead-end нет `connect_profile` до revival-verif; synthetic-limit срабатывает ≤90с |
| 762 (плашка «Нет рабочих серверов» после exhausted) | revival watch exhausted → `all_servers_dead_prompt_timeout` → плашка «Нет рабочих серверов» (не немое «Остановлено»); сброс при тапе/Connected/activity | поле 21.08 (код 761): юзер «нажимаю Подключить на БС — ничего не подключается», пустая БД подписок на BS → пул=1 → exhausted → UI молчал. Проверить: тап на BS с мёртвыми серверами → 6 мин watch → exhausted → плашка, OCR title/subtitle |
| 763 (линейное построение MERGED-пула) | `stratifiedSample` пересортировывал все группы в каждом раунде round-robin (квадрат) — на MERGED-пуле с доминирующей WL-группой `H4→H24` ~26с → prepare не укладывался в 30с adapt-таймаут → reload мёртвого LKG-профиля → бесконечный цикл 502 | поле 21.08 (код 761): 5+ циклов по ~51с `502 Bad Gateway` на 1722, пул 4096 никогда не зондировался. После фикса (код 763): `H4→H24` 1.96с, тап→Connected 24.3с, `post_connect_url_test_success delayMs=376`. Проверить: `H4→H24` ≤~3с при subsWlMarked>5000; нет 502-цикла |
| 764 (сброс залипшего «Подготовка» после adapt-timeout) | `wl_adapt_prepare_timeout` без requiresTunnelRebuild (sub_transport_recover) → `wl_adapt_timeout_activity_clear` → `simpleModeActivity=""` → UI возвращается в Connected (тон не PREPARING) | поле 21.08 (код 761): после adapt-timeout туннель жив (session_periodic ok), но ни одного activity-write после «Verifying last server…» — UI залип в «Подготовка». Проверить: при Connected сделать handoff/sub_transport_recover → после завершения/таймаута адаптации UI «Подключено», не «Подготовка» |
