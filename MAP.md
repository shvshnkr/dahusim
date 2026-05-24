# MAP

## Entry points
- Android: `MainActivity` → `MainScreen` (click-to-connect UI, Koin+Compose) + `SagerConnection` binds to VPN service.
- Desktop: `fr.husi.DesktopMainKt` launcher + `DesktopMain` command line controls.
- Background/simple mode: `SimpleModeConnectCoordinator`/`SagerConnection` orchestrate quick connect.

## Основные модули
- `ui/*`: главный экран, навигация, настройки, экраны подписок, viewmodels.
- `simplemode/*`: адаптация сети, подключение, health checks, permissions, auto reconnect.
- `bg/*`: сервисное управление, deep links, process pool, subscription auto-update, network resolver.
- `database/*`: Room/Datastore schemas, proxies/groups, AutoServerSelector, ProbeScheduler, keystore.
- `group` + `subscription/catalog`: подписки, fetchers, embedded catalog applier, raw/SIP008 updaters.
- `update/*`: проверка app update manifest, скачивание, evaluator, repository.
- `fmt/ConfigBuilder` + `libcore/BoxServiceFactory`: конфиги sing-box, VPN execution.

## Networking flow
- `SagerConnection` + `libcore` bridge генерирует sing-box конфиг, запускает VPN tunnel.
- `database/AutoServerSelector` и `ProbeScheduler` наполняют пул прокси/сервера, `bg/NetworkResolver` обновляет DNS.
- `SimpleModeNetworkProbe` тестирует URL, `SimpleModeHealthRoute` следит за состоянием.

## VPN connection flow
- `SimpleModeConnectCoordinator` запускает auto-select, проверяет разрешения (`SimpleModeVpnPermission`) и здоровье сети.
- `AutoServerSelector.prepareForConnect` выбирает профиль, `SagerConnection` стартует `BoxServiceFactory`/sing-box и пишет `ServiceState`.
- `bg/GuardedProcessPool` управляет lifecycle, `SimpleModeConnectedMaintenance` поддерживает автообновления подписок во время сессии.

## Subscription/update flow
- `group/*` и `SubscriptionUpdater` фетчат ссылки, `SubscriptionCatalogCoordinator` подхватывает встроенные каталоги и сохраняет в DB.
- `bg/SubscriptionAutoUpdate` запускает фоновые обновления, `subscription/catalog` парсит, `resolvers` обновляют pool.
- `update/*` проверяет app-update manifest, скачивает/вписывает обновления при включенном `appUpdateCheckEnabled`.

## Config/storage flow
- `DataStore` (preference) хранит настройки клинета, состояния авто-подбора, тайминги обновлений, флаги simple mode.
- `SagerDatabase` (Room) сохраняет `ProxyEntity`, `ProxyGroup`, `SubscriptionBean`, статистику, используется `ProfileManager`, `AssetEntity`.
- `fmt/ConfigBuilder`, `database/AssetEntity`, `repo` собирают конфиги sing-box и передают в `libcore`.

## Server selection logic
- `AutoServerSelector` + `AutoServerSelectorProbePolicy`/`SessionFallback` выбирают лучшие сервера по probe-state, `AutoServerSelectorSessionFallback` хранит last-known-good.
- `database/ProbePoolEligibility`, `ProbeScheduler`, `SimpleModeNetworkProbe` обеспечивают тестирование URL/speed.
- `routing/WhitelistRuRouting` и `BuiltinRouteRuleGuard` с `SimpleMode` флагами управляют whitelist-only режимом и RU-геосайтами.

## UI/state management
- `MainViewModel`/`MainScreenScope`, `SubscriptionCatalogSettings`/`Navigator` хранят состояние клика, выбранного профиля, подписок.
- `RouteScreenViewModel`, `GroupScreenViewModel`, `SimpleMode` компоненты, `Probe2kSettings`, `AppUpdateScreen` отражают состояние хранилища через `DataStore`/`Repository`.
- Koin DI (`di/Koin.kt`, `Navigation.kt`) связывает viewmodels, `DeepLinkDispatcher` реагирует на внешние запросы.

## Ключевые зависимости
- Compose Multiplatform (runtime, material3, animation, components resources) + desktop targets.
- Koin (core, compose, navigation, viewmodel) for DI/state.
- Room + Datastore preferences + SQLite bundled.
- libcore (Go sing-box wrapper) via `BoxServiceFactory` + desktop jar.
- Kryo, ini4j, FileKit, aboutlibraries, zxing, plugin modules.
