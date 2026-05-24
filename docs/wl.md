# Модель поведения Simple Mode при WL, нормальной сети и ЧС

Данный материал описывает, какие переменные (`DataStore`, `NetworkReachabilityProbe`, `WhitelistNetworkRoutingState`) отражают состояние сети, как оно влияет на авто-подбор серверов, health checks, фоновые обновления и перезапуск туннеля, а также показывает три характерных сценария: белый список (WL/БС), обычная сеть (Google жив) и «черный список/цензура» (ЧС).

## 1. Как определяется состояние сети

1. `NetworkReachabilityProbe` (`composeApp/src/androidMain/kotlin/fr/husi/bg/NetworkReachabilityProbe.kt`) делает прямые (uplink) запросы к Google (`generate_204`), `dzen.ru`, `ya.ru` и нескольким источникам списка (whitelist.txt). Поля структуры `NetworkReachability`:
   - `googleReachable`, `dzenReachable`, `yaReachable`, `whitelistSourceReachable` — каждый отражает успешный прямой доступ.
   - `hasInternet` = есть ли любой из этих ответов.
   - `whitelistOnly` = Google мертв, но `dzen` или whitelist-зона доступны — сигнал о том, что uplink работает только по списку.

2. `BaseService` при старте (`resolveConnectReachability`) ставит:
   - `DataStore.simpleModeUseWhitelistBuiltinPoolOnly = reachability.whitelistOnly` (SimpleModeConnectCoordinator и SimpleModeNetworkAdaptation тоже делают это при повторном авто-подборе).
   - `DataStore.activeWhitelistRestrictedNetwork = reachability.whitelistOnly` и очищает флаг `autoConnectPausedUntilGoogle` (если дошли до WL).
   - При отсутствии интернета `DataStore.autoConnectPausedUntilGoogle = true` (ждет появления Google для автоперезапуска).
   - `WhitelistNetworkRoutingState.applyReachability` обновляет флаг маршрутизации и, если надо, вызывает `SimpleModeVpnCoordinator.scheduleAdaptation`.

3. `SimpleModeTunnelRestart` кеширует последний `NetworkReachability` на ~45 с, чтобы в процессе перезапуска не перезапрашивать сеть.

## 2. Автоподбор серверов и перезапуск

- `AutoServerSelector.prepareForConnect` отталкивается от `DataStore.simpleModeUseWhitelistBuiltinPoolOnly`: если `true`, построение пула идут по `ConnectPoolPolicy.buildWhitelist` (в первую очередь встроенные WL-профили + подписки + лимит `WL_PREPARE_CAP`), иначе — по `buildOpen` (включая `priority = handoffIds`, большой cap, больше шагов отката).
- При смене сети/проваленом health check `SimpleModeVpnCoordinator` вызывает `SimpleModeNetworkAdaptation.reselectForNetwork`, который:
  1. Устанавливает `DataStore.simpleModeUseWhitelistBuiltinPoolOnly = reachability.whitelistOnly`.
  2. Запускает `AutoServerSelector.prepareForConnect` с `owner = ADAPT` (есть дедуплекс, дебаунс, таймауты 30–45 с).
  3. Информация о смене reachability записывается в `DataStore.activeWhitelistRestrictedNetwork`, а также `DataStore.autoConnectPausedUntilGoogle = false`, если теперь WL.
- `SimpleModeVpnCoordinator` после выбора нового профиля вызывает `SimpleModeTunnelRestart.markModeReconnect` (WL vs open) и `ServiceRegistry.baseService?.reload()` (или `resolveRepository().reloadService()`).
- `SimpleModeSessionHealth` проверяет туннель каждые 30 с: если `DataStore.activeWhitelistRestrictedNetwork == true`, устанавливает лимит `CONSECUTIVE_FAIL_LIMIT = 1` (иначе 2) и вызывает `AutoServerSelector.tryMoveToFallback`. При исчерпании очереди — вызывает `SimpleModeVpnCoordinator.scheduleAdaptation` или остановку.

## 3. Health checks и целевые URL

- `SimpleModeHealthRoute` хранит матрицу:
  - WL/BS: health-цели берутся из `tunnelBsProbeUrls()` (по умолчанию `https://web.telegram.org`).
  - Open-интернет: используется gstatic + пользовательский `DataStore.connectionTestURL`.
- `skipTunnelHealthCheck(whitelistOnly)` (с флагом `DataStore.simpleModeWlSkipTunnelHealthCheck`) пропускает проверку туннеля на белых сетях, если требуется.
- `postConnectTimeoutMs`, `warmupMs` и `maxAttempts` тоже адаптируются под WL (большее время ожидания и больше попыток), а `SimpleModeSessionHealth` перед health check повторно вызывает `NetworkReachabilityProbe.probe(fast = true)` и обновляет `DataStore.activeWhitelistRestrictedNetwork`.
- Если health check провалился, `SimpleModeVpnCoordinator.tryRecoverAfterUnhealthySession` проверяет: был ли fail `inconclusive` (например, underlying WL dial), в этом случае просто переключает флаг и не перезапускает туннель.

## 4. Фоновые обновления и планировщики

- Перед подключением `SimpleModeConnectCoordinator` пытается `SubscriptionAutoUpdateRunner.refreshDueWithBudget` (бюджет больше для WL/BS). Если все узлы «мёртвые», пользователь может выбрать `WaitForGoogle` — устанавливает `autoConnectPausedUntilGoogle = true`.
- `SimpleModeConnectedMaintenance` после успешного соединения собирается обновить подписки в фоне, но:
  - Пропускает, если сейчас WL и `postConnectLatencyMs` слишком велик, либо Google/whitelistSource не подтверждены (см. `whitelistChannelConfident`).
  - Пропускает, если подключились на WL, а сейчас всё ещё WL (чтобы не перегружать заряд).
- `ProbeScheduler` фонит TCP-пробами, но не стартует при `DataStore.serviceState.connected` и отсеивает WL-пулы, если `DataStore.activeWhitelistRestrictedNetwork == true` (отдает приоритет встроенным WL-профилям).

## 5. Примеры сценариев

### 5.1. WL / БС / White lists (Google мёртв)

- Детект: `googleReachable = false`, `dzenReachable || whitelistSourceReachable = true` ⇒ `NetworkReachability.whitelistOnly == true`.
- `DataStore.simpleModeUseWhitelistBuiltinPoolOnly = true`, `activeWhitelistRestrictedNetwork = true`, `autoConnectPausedUntilGoogle = false`.
- `AutoServerSelector` строит пул из встроенных WL-хелперов и подписок, `ConnectPoolPolicy.WL_PREPARE_CAP = 128`, ранжирует `wlNodeRank` (builtin > подписки > остальное).
- Health checks идут по `SimpleModeHealthRoute.tunnelBsProbeUrls` (`web.telegram.org`), таймауты больше, попыток до 3, `SimpleModeSessionHealth` держит `consecutiveFails` до 1.
- `SimpleModeVpnCoordinator` в адаптации игнорирует debounce (быстрый reselect при `reachability_flip`, `network_handoff`, `session_health_exhausted`).
- `SimpleModeConnectedMaintenance` обновляет подписки только при уверенности (низкая латентность, доступ к Google или whitelist-URL) и ставит `simpleModeLastBackgroundSubRefreshAt`.
- Если health check упал, `SimpleModeVpnCoordinator.tryRecoverAfterUnhealthySession` сначала пытается `tryMoveToFallback`, затем (если очередь кончилась) останавливает сервис или переводит в `autoConnectPausedUntilGoogle` только когда сеть стала open.

### 5.2. Normal / Google reachable (Open)

- `NetworkReachability.whitelistOnly = false`, `googleReachable = true`.
- `DataStore.simpleModeUseWhitelistBuiltinPoolOnly = false`, `activeWhitelistRestrictedNetwork = false`, `autoConnectPausedUntilGoogle` очищается.
- `AutoServerSelector` собирает полный пул (`ConnectPoolPolicy.buildOpen`), допускает очередь `MAX_SESSION_FALLBACK_STEPS_OPEN = 32`, `subscriptionCompactReprobe` используется когда состав пула поменялся.
- Health check targets: Cloudflare/gstatic + `DataStore.connectionTestURL`; `SimpleModeSessionHealth` после двух провалов переключит сервер, а затем `SimpleModeVpnCoordinator` может перезапустить или остановить.
- Фоновые обновления и ProbeScheduler свободно работают (`SimpleModeConnectedMaintenance` всегда можно провести `SubscriptionAutoUpdateRunner.refreshDueWithBudget`, `ProbeScheduler` поддерживает пул).
- При потере интета `BaseService` установит `autoConnectPausedUntilGoogle = true` и ждёт, пока Google появится снова.

### 5.3. BlackList / ЧС (Google жив, но часть ресурсов блокирована)

- Состояние сети всё ещё `whitelistOnly = false`, но пользователю нужен VPN для обхода конкретного блокирования.
- Автоподбор и health checks ведут себя как в open-сети: стандартные URL, лимит 2 подрядных failures.
- `SimpleModeSessionHealth` всё ещё переключается при ухудшении качества (например, блокировка Telegram без VPN будет замечена как health fail), и `SimpleModeVpnCoordinator.tryRecoverAfterUnhealthySession` прыгает по очереди.
- `SubscriptionAutoUpdateRunner` / `ProbeScheduler` работают без ограничений. Если пакет `autoConnectPausedUntilGoogle` был поднят (например, попытка подключиться без интернета), при детекте Google-сервиса он сбрасывается и возобновляется.
- «ЧС» важно только для пользователя/маршрутизации: код не держит отдельный флаг, но поведение напоминает open-сеть (т.е. дополнительные проверки/сключения не добавлены).

## 6. Ключевые переключатели и флаги

| Параметр | Что делает | Где меняется |
| --- | --- | --- |
| `DataStore.simpleModeUseWhitelistBuiltinPoolOnly` | заставляет `AutoServerSelector` строить WL-пул и `ConnectPoolPolicy` применить `WL_PREPARE_CAP` | `SimpleModeConnectCoordinator`, `SimpleModeNetworkAdaptation`, адаптация `SimpleModeVpnCoordinator` |
| `DataStore.activeWhitelistRestrictedNetwork` | отражает фактическое ограничение uplink; влияет на health checks, `SimpleModeSessionHealth`, `ProbeScheduler`, кап фоновых обновлений и флаг `SimpleModeActivity` | `WhitelistNetworkRoutingState`, `SimpleModeSessionHealth`, `SimpleModeVpnCoordinator` |
| `DataStore.autoConnectPausedUntilGoogle` | ставится при отсутствии интернета / когда все профили кончились (`SimpleModeConnectCoordinator`, `BaseService`). Сбрасывается, как только `reachability.googleReachable == true` | `BaseService`, `SimpleModeVpnCoordinator.tryRecoverAfterUnhealthySession`, dialogs в UI |
| `SimpleModeActivity` | отображает текущее состояние («Проверка сети», «Ищем сервер», «Переключаемся…») и не обновляется, если сервис уже коннект | `AutoServerSelector`, `SimpleModeConnectCoordinator`, `SimpleModeVpnCoordinator`, `SimpleModeSessionHealth` |
| `DataStore.simpleModeWlSkipTunnelHealthCheck` | debug-флаг, который позволяет пропустить туннельный health check на WL, тогда `SimpleModeSessionHealth` просто возвращает `true` | `SimpleModeHealthRoute.skipTunnelHealthCheck` |

Суммарно: простая модель опирается на прямой probing (google/dzen/ya/whitelist), флаги в `DataStore` и адаптационные координаторы (`SimpleModeVpnCoordinator`, `SimpleModeSessionHealth`, `SimpleModeTunnelRestart`). Знание того, какая комбинация reachability флагов включена, позволяет предсказать поведение авто-подбора, health check-фазы и фоновых обновлений.