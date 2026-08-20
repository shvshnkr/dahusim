# Промпт на имплементацию редизайна (скопируй целиком)

> Куда пасти: в новую сессию агента в ветке `redesign` репозитория DaiHusim
> (рабочая копия уже на ветке `redesign`). Если ветка не там:
> `git checkout redesign && git merge main` (при конфликте в `husi.properties` —
> VERSION_NAME/VERSION_CODE из main, `VERSION_NAME_SUFFIX=redesign` сохранить).

---

## Задача

Реализовать в ветке `redesign` утверждённые мокапы простого экрана и управления
подписками. Мокапы и концепты приняты пользователем — их структура обязательна.
НО главный приоритет — **работоспособность и сохранение всей существующей логики**:
не «новый дизайн поверх», а аккуратная пересборка UI-слоя без потери функций,
с полной проверкой пользовательских сценариев (копипаста подписок, импорт,
user-agent, permission-флоу, обновление подписок).

Принятые артефакты (читать перед кодом):
- `docs/design/simple-screen-redesign/concept.md` + `mockup.html`
- `docs/design/subscription-redesign/concept.md` + `mockup.html`

Скоуп ветки: только Compose/UI/тема/навигация. Бизнес-логика не меняется.

## Твёрдые правила (нарушение = возврат на доработку)

1. **Никаких заглушек и выдуманных данных.** Каждый элемент мокапа рисуется из
   реального состояния, которое уже есть в коде:
   - статус: `BackendState.status` / `ServiceState` / `statusTone` (SimpleHomeScreen),
   - активность: `DataStore.SIMPLE_MODE_ACTIVITY`,
   - скан: `DataStore.PROBE_2K_SCAN_TOTAL/CHECKED` + `Probe2kProgress`,
   - подписки: `GroupItemUiState` (group, counts, isUpdating), `group.subscription`
     (bytesUsed/bytesRemaining/expiryDate), `connectPoolRole`.
   Если в модели нет поля (напр. «ошибка последнего обновления» или «время последнего
   обновления») — сначала прочитай модель `Subscription`/`ProxyGroup` и используй
   только реальные поля; отсутствующие элементы мокапа скрывай, а не фабрикуй.
2. **Все существующие функции обязаны продолжать работать как раньше.** Перед правкой
   каждого файла найди и прочитай его текущую реализацию и вызывающих. После правки —
   пройдись по сценариям из чек-листа §4 и убедись, что ничего не сломалось.
3. **Не менять сигнатуры и поведение:**
   - `SimpleModeConnectCoordinator.start(connectHost)` / `isInFlight()`,
     `ConnectHost` callbacks, `SimpleModeVpnSessionMarker`,
   - `MainViewModel`: `parseProxy`, `importProfile`, `importSubscription`,
     `updateSubscriptionGroup`, `updateAllSubscriptionGroups`, `showSnackbar`,
     `promptSimpleModeAllServersDead`,
   - `GroupScreenViewModel`: `uiState`, `commit`, `undoableRemove`, `undo`,
     `submitSegmentReorder`, `hiddenGroups`; `ManualServersViewModel.undo`,
   - `ImportLinkClassifier`, `DataStore` ключи, `Probe2kProgress.publishScan`.
4. **User-Agent при фетче подписки не трогать.** Никаких «улучшений» UA, custom UA —
   только через существующие настройки (`SubscriptionFetchProfileBlock`,
   `customUserAgent` в каталоге). История: правки UA ломали совместимость
   с subconverter — не повторять.
5. **Фикс «Сканирование 1/1 висит»** — обязательная часть (см. §3, пункт 1).
6. **Строки — только через strings.xml** (composeResources), цвета/типографика — из
   MaterialTheme. Никаких хардкодов текста/цветов в Compose.
7. Код в `commonMain` должен компилироваться и под десктоп-таргет (UI общий).
8. Маленькие диффы, стиль — по `CONTRIBUTING.md`; без speculative-guards и
   комментирования кода (pop-комментариев).
9. **VERSION_CODE: в ветке redesign не bump'ить** на UI-коммитах (правило
   `docs/REDESIGN_BRANCH.md`). `VERSION_NAME_SUFFIX=redesign` в `husi.properties` — не удалять.
10. Редизайн не должен ломать H-коды и логирование (`simpleModeLog("SimpleMode", "H11 …")`
    и т.п.) — журнал простого режима используется для диагностики по логам.

## Что реализовать

### Фаза 1. Простой экран (`composeApp/src/commonMain/kotlin/fr/husi/ui/simple/`)

1. **Фикс «1/1 висит»** (корневая причина): `AutoServerSelector` публикует
   `publishScan(done, total)`, но после завершения prepare никто не вызывает
   `Probe2kProgress.clearScan()` — строка «Scanning N/N» остаётся. Сделать:
   - `clearScan()` при завершении prepare-пайплайна (выход из select/подключение),
   - в UI: прогресс рисуется только пока `checked < total` и состояние не
     Connected/Stopped; таймаут скрытия 8с без обновления `checked`.
   Убедись, что прогресс по-прежнему виден во время реального сканирования
   (дуга кольца = checked/total), а после завершения — исчезает.
2. **`SimplePowerButton.kt`** — новый компонент: круглая кнопка 120–160dp + кольцо
   статуса. Тоны — существующие цвета `StatusTone` из `SimpleHomeScreen.kt`
   (STOPPED=error-тон, PREPARING=#5C6BC0, CONNECTING=#C58A00, CONNECTED=#2E7D32).
   Состояния: stopped (серый контур, power-иконка), preparing (пульсирующая дуга —
   прогресс сканирования checked/total), connecting (вращающаяся дуга),
   connected (полное кольцо + мягкое свечение). Permission-pending — spinner-дуга.
   Анимации стандартные M3 + уважение `reduceMotion`/`isAccessibilityEnabled` где можно.
3. **`SimpleHomeScreen.kt`** — пересборка макета по мокапу:
   - убрать три карточки (описание/статус/WL-рамка); описание — только в первый раз
     или скрыть (в мокапе его нет; если решишь оставить подсказку — компактной строкой,
     не карточкой),
   - WL-баннер → тонкая chip-строка над кнопкой (только при
     `DataStore.activeWhitelistRestrictedNetwork`, текст — существующие
     `simple_mode_wl_banner_title/subtitle`),
   - статус: headline + detail под кнопкой; detail — как сейчас (приоритет:
     permission → scan → activity → pool, `displaySimpleModeActivity`),
   - низ экрана: вторичные действия (Share logs, Full UI) — как в мокапе,
   - `SimpleModeUncleanStopNotice` — стилизовать тонко, логика показа не меняется,
   - весь connect/disconnect/permission флоу, snackbar'ы, `releaseSimpleModeVpnSession`
     и `resolveRepository().stopService()` — **идентично текущему коду**.
4. Пустые состояния, большой текст (шрифт системы), поворот экрана — не падать.

### Фаза 2. Карточка подписки (`ui/library/LibraryScreen.kt`)

1. **`LibraryGroupCard`** → карточка-статус по мокапу:
   - аватар-эмодзи/иконка, имя, role-чип (WL/OPEN/builtin по `connectPoolRole` /
     `GroupOrigin.BUILTIN`),
   - счётчик серверов (`proxyCountLabel`, plurals),
   - usage-бар + «использовано/осталось» + срок (`subscription_traffic`,
     `subscription_expire`; оранж <7 дней, красный — просрочено; скрывать если нет данных),
   - статус: `isUpdating` → спиннер + «обновление…»; ок/устарела/ошибка — только из
     реально существующих полей модели (проверь модель; нет поля — не показывай),
   - tap → `openGroup` (детали), ⋮ → существующий options-шит (share/QR/edit/delete),
   - свайп-действия (обновить/удалить с undo) — используй уже подключённую
     `DragDropSwipeLazyColumn` инфраструктуру или M3 `SwipeToDismissBox`; undo —
     существующий snackbar-механизм `hiddenGroups`/`undo`.
2. Топ-бар: поиск (фильтр по displayName/ссылке), «обновить всё» без confirm-диалога
   (спиннер в иконке; `updateAllSubscriptionGroups()`), reorder — как есть.
3. Сегменты (`LibrarySegmentRow`) → табы со счётчиками (посчитать по
   `matchesSegment`), остаётся 3 сегмента.
4. Фильтр ролей `[Все][WL][OPEN]` — только на сегменте Subscriptions, по
   `subscription.connectPoolRole` (токены `poolRoleToken`); при WL-фильтре — строка-
   подсказка про simple-режим. Сортировку порядком сегмента сохранить (reorder).
5. **`LibraryAddSheet`** — clipboard-карточка сверху (preview первой строки буфера +
   кнопка «Импорт»), остальные строки — как сейчас.

### Фаза 3. Тесты и гейты

1. Юнит-тесты (локально `desktopTest --tests "<класс>"`):
   - фильтр ролей/поиска/счётчиков сегментов (чистые функции — вынести в
     testable-функции, если нужно),
   - `Probe2kProgress`: clearScan по завершении prepare (если добавишь вызов — тест на
     публикацию/очистку).
2. Journey: обновить/добавить сценарии в `FeatureJourneys.kt` registry:
   - simple screen: статусы кнопки, исчезновение scan-прогресса после prepare,
     WL-chip при whitelistOnly,
   - library: фильтр ролей, поиск, обновление подписки с прогрессом, удаление с undo.
   CI-гейт `featureJourneyTest` (и `fieldLogScenarioTest`) должны остаться зелёными —
   проверяется на GitHub Actions (`bash buildScript/ci/gh-workflow.sh build`), локально
   полные прогоны не гонять.
3. После правок — `bash buildScript/ci/gh-workflow.sh build --ref redesign --android --wait`
   (или как принято в `docs/REDESIGN_BRANCH.md`), фиксить регрессии до зелёного.

## Чек-лист пользовательских сценариев (проверить руками в коде, что не сломались)

Каждый пункт — проследить путь по коду от UI до результата, убедиться, что редизайн
его не обрезал:

1. **Копипаста подписки из буфера**: в Library → Добавить → «Создать подписку» →
   в буфере vless:// одной строкой → импорт как профиль; в буфере http(s)-URL
   подписки → `ImportLinkClassifier.looksLikeSubscriptionUrl` → подписка; в буфере
   GitHub-URL → классификация как Subscription даже при 1 прокси; в буфере
   многострочный текст — берётся первая строка, поведение как раньше.
2. **Прямой тап «Импорт» на clipboard-карточке** (новый элемент) — должен идти тем же
   путём, что и «Создать подписку» с буфером: `clipboard.getPlainText()` →
   `ImportLinkClassifier` → `parseProxy`. Не должен падать на пустом буфере.
3. **User-Agent фетча подписки**: обновление подписки после редизайна работает так же
   (не менять UA; если у подписки настроен custom UA — он продолжает применяться).
4. **Обновить всё**: все карточки показывают isUpdating, повторный тап не дабл-запускает
   (проверить как защищено сейчас — сохранить), snackbar об ошибках.
5. **Удаление с undo**: свайп/меню → snackbar «Удалено» → undo восстанавливает;
   то же для Manual-сегмента (`ManualServersViewModel`).
6. **Reorder**: drag&drop в reorder-режиме сохраняет порядок (`submitSegmentReorder`),
   свайп-жесты не конфликтуют с drag.
7. **Simple-экран**: connect из любого состояния, permission-диалог системы,
   keyguard/foreground-подсказки, отмена in-flight, disconnect, unclean-stop notice,
   battery-кнопка, «Полный интерфейс» — всё как было (H11-логи на месте).
8. **QR/NFC сканирование** и импорт из файла — как было.
9. **Тёмная/светлая тема** — через существующий механизм темы, без жёстких цветов.

## Чего НЕ делать

- Не трогать: reachability/tunnel-health (`docs/BS_CS_NETWORK.md`), WL/BS-логику,
  `AutoServerSelector`, `SimpleModeConnectCoordinator` внутренности, модели БД
  (`Subscription`/`ProxyGroup`), DataStore-ключи, движок/нативы.
- Не «улучшать» user-agent, DNS, health-пробы — вне скоупа редизайна.
- Не коммитить в main; все коммиты — в `redesign`, по одной логической единице,
  сообщения в стиле репозитория (англ., кратко, ссылка на VERSION_CODE не нужна).

## Критерий готовности

- Все фазы реализованы, ни один пункт чек-листа §4 не сломан (проверено по коду),
- `desktopTest` по затронутым классам зелёный локально,
- `featureJourneyTest` + android-сборка зелёные на CI для ветки `redesign`,
- нет хардкода строк/цветов, нет заглушек и выдуманных данных,
- апдейт `AI/cursorworklog.md` (map_sync) и, при необходимости, `FeatureJourneys.kt`.

По завершении каждой фазы — короткий отчёт: файлы, изменения, что проверено,
что не удалось (если что-то из мокапа не реализуемо без правки модели — назвать это
явно вместо тихого пропуска).
