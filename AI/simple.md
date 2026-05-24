# Простая логика simple mode

## Пре-подключение
- `SimpleModeConnectCoordinator` запускается по click-to-connect: начинает `probeSimpleModeNetwork`, записывает `DataStore.simpleModeActivity`, учитывает whitelist-only, разные бюджеты обновления подписок.
- Параллельно запускается `SubscriptionAutoUpdateRunner` с таймаутом (200–8000 мс) для быстрых сетов; потом `AutoServerSelector.prepareForConnect` выбирает профиль (owner = CONNECT) и, при успехе, ждёт `awaitSimpleModeVpnPermissionUi`, вызывает `host.requestVpnConnect()`.
- Нет интернет → `ConnectHost.onNoInternet`; все сервера мёртвы → `SimpleModeAllServersDeadChoice` с паузой до «гугл» или выходом.

## Жизненный цикл и здоровье
- Запущенный туннель отслеживается `SimpleModeSessionHealth`: периодически (30 с) и по запросу запускает `SimpleModeTunnelHealthCheck` через `Libcore` по URL из `SimpleModeHealthRoute`; подсчитывает streaks, обновляет `AutoServerSelector` и при деградации вызывает `SimpleModeVpnCoordinator`/фолбэки.
- `SimpleModeHealthRoute` определяет набор URL для open/whitelist, timeout, логи и инконклудиз; `SimpleModeTunnelRestart` сохраняет недавнюю достижимость TLS.
- `SimpleModeConnectedMaintenance` планирует фоновые обновления подписок (каждые 45 мин) только если туннель здоров и тесты (google, whitelist source) проходят.

## Адаптация и recovery
- `SimpleModeVpnCoordinator` — центральное тело адаптации: при смене сети, reachability flip, health-fail_schedule пробует `SimpleModeNetworkAdaptation.reselectForNetwork` (owner = ADAPT) с дедублирующим мьютексом, дебаунсом и таймаутами 30–45 с; при новом профиле обновляет `DataStore.selectedProxy`, отмечает `SimpleModeTunnelRestart`, перезапускает сервис.
- При неудаче/истощении `AutoServerSelector.tryMoveToFallback` либо `resolveRepository().stopService()`, `SimpleModeVpnCoordinator` может записать `autoConnectPausedUntilGoogle` и ждать ручного возвращения в приложение.

## Переменные состояния
- `DataStore` держит флаги `simpleMode`, `activeWhitelistRestrictedNetwork`, `simpleModeActivity`, `autoConnectPausedUntilGoogle`, `simpleModeUseWhitelistBuiltinPoolOnly` и параметры авто-подбора, используемые всеми компонентами.
- `SimpleModeActivityText` классифицирует статусные строки для UI и лога `SimpleModeConnectCoordinator`/`vpnCoordinator`.

## UI/permission hooks
- Ожидание разрешения VPN (`awaitSimpleModeVpnPermissionUi`) через `UiActivityTracker` (Android) и мгновенно на desktop; `SimpleModeConnectCoordinator` обновляет UI-статусы (`host.setPermissionPending`, `host.requestVpnConnect`).
- `SimpleModeNetworkProbe` использует `NetworkReachabilityProbe` (android) для определения доступа к интернету и whitelist.

## Логи и debug-события
- Каждый критический шаг логируется (`simpleModeLog`, `simpleModeDebugEvent`) с метками H21–H37, позволяя трассировать стадии preconnect, adaptation, health, background refresh.
