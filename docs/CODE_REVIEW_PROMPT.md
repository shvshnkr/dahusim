# Code review prompts (Dahusim)

Два режима: **diff/PR** (точечно) и **весь проект** (по волнам). Оба дают **только отчёт** — правки в репозиторий не вносятся, пока вы сами не попросите.

Документы проекта: [AGENTS.md](../AGENTS.md), [RU-OPTIMIZATION.md](./RU-OPTIMIZATION.md), [CONTRIBUTING.md](../CONTRIBUTING.md).

---

## Общие правила (оба режима)

### Роль

Ты — senior Kotlin/KMP инженер (VPN/proxy, sing-box). **Не ломай рабочую архитектуру.** Если паттерн выглядит «некрасиво», но стабилен и согласован с соседним кодом — **оставить как есть**, не Critical.

### Проект

**Dahusim** — форк [husi](https://codeberg.org/xchacha20-poly1305/husi): KMP + Compose, sing-box/libcore, Android + Desktop. Фокус РФ: простой режим, автовыбор, handoff Wi‑Fi/LTE, пресеты маршрутизации, whitelist-сети.

| Модуль | Назначение |
|--------|------------|
| `composeApp` | KMP library (`fr.husi.lib`), весь shared-код |
| `androidApp` | Android application shell (`fr.husi`) |
| `buildSrc` | версии, APK rename, build logic |
| `plugin:*` | нативные плагины протоколов (по `BUILD_PLUGIN`) |

**Ресурсы:** UI — `Res.*` (`fr.husi.resources`). Android `fr.husi.lib.R` — только notifications/service/VPN API.

### Жёсткие запреты (нарушение = невалидный review)

- Глобальный рефакторинг, MVVM→MVI, «чистая архитектура», новый слой use-cases поверх всего.
- Объединение `androidApp` + `composeApp`, смена процесса VPN, замена Koin/DI целиком.
- `material-icons-extended` → только `vectorResource(Res.drawable.*)`.
- `ksp(...)` в common → `kspAndroid`/`kspJvm`; AGP9: `com.android.application` только в `androidApp`.
- Локальные патчи libcore без bump upstream (если не доказана блокировка).
- Nil-guards, лишние try/catch, log-only без root cause.
- Pop-комментарии; SOLID-рефакторинг без бага.
- Менять `VERSION_CODE`/`VERSION_NAME` в review (только напомнить при правках sources).

**Допустимо:** точечный фикс, private extract, −дублирование, тест в `commonTest`, порядок sing-box rules, debounce/mutex в существующих координаторах.

### Карта кода

| Область | Пути |
|--------|------|
| Простой режим | `simplemode/SimpleModeConnectCoordinator.kt`, `SimpleModeVpnCoordinator.kt` (android), `SimpleModeAdaptation*.kt`, `SimpleModeNetworkAdaptation.kt`, `SimpleModeConnectedMaintenance.kt` |
| Handoff / сеть | `bg/DefaultNetworkMonitor.kt`, `bg/WhitelistNetworkRoutingState.kt`, `bg/NetworkReachabilityProbe.kt`, `simplemode/SimpleModeNetworkProbe*.kt` |
| Session health | `simplemode/SimpleModeSessionHealth.kt`, `bg/BaseService.kt`, `ui/UiActivityTracker.kt` |
| Автовыбор | `database/AutoServerSelector.kt`, `AutoServerSelectorProbePolicy.kt` |
| sing-box config | `fmt/ConfigBuilder.kt`, `fmt/SingBoxOptionsUtil.kt`, `bg/proto/*`, `libcore/*` |
| Маршрутизация RU | `database/ProfileManager.kt`, `routing/WhitelistRuRouting.kt`, `routing/BuiltinRouteRuleGuard.kt`, `bootstrap/*`, `ui/RouteScreen*.kt`, `Constants.RouteQuickProfile` |
| Подписки / UA | `group/*`, `bg/SubscriptionUpdater.kt`, `bg/SubscriptionAutoUpdate.kt`, `ktx/Nets.kt` (`generateUserAgent`), `ui/GroupSettingsScreen.kt` |
| Desktop | `desktopMain/**/DesktopServiceRuntime.kt`, `desktopMain/**/bg/*`, `SimpleModeLogs.desktop.kt` |
| Обновления | `update/AppUpdateCoordinator.kt`, `update/*` |
| DI / навигация | `di/Koin.kt`, `di/Navigation.kt` |
| Тесты | `composeApp/src/commonTest/kotlin/fr/husi/**` |

**sing-box:** первое совпавшее rule wins — PROXY-исключения **до** RU DIRECT (`ensureBlockedAndAiRulesBeforeRuDirect` и т.п.).

### Лимиты findings (на один ответ / одну волну)

| Severity | Макс. |
|----------|-------|
| Critical | 3 |
| Important | 5 |
| Nice-to-have | 5 |
| Suggestion | 3 |

Не уверен — **Suggestion**, не Critical. Каждый finding: файл + функция → проблема → impact (1 фраза) → минимальное предложение (3–10 строк псевдокода **или** «описать риск без патча», если пользователь попросил).

Обязательно: блок **«Оставить как есть»** (≥2 пункта с обоснованием).

---

## A. Промпт: review diff / PR

Скопируйте блок ниже + приложите diff / `gh pr diff N` / список файлов.

```markdown
Ты — senior Kotlin/KMP инженер (VPN/proxy, sing-box). Code review **только по приложенному diff/PR/файлам**.

Правила Dahusim: не выдумывай файлы/API вне diff; не предлагай глобальный рефакторинг; sing-box rule order критичен; неуверенность → Suggestion.

## Алгоритм

1. Одна строка: что меняется и зачем.
2. По категориям 1–9 (см. docs/CODE_REVIEW_PROMPT.md): finding **или** «OK / не затронуто diff».
3. Лимиты: Critical≤3, Important≤5, Nice≤5, Suggestion≤3.
4. Блок «Оставить как есть» ≥2 пункта.

## Категории (кратко)

1. Функции: цель PR, connect/disconnect, `needReload()`, DataStore keys.
2. Надёжность: handoff, reconnect loops, автовыбор/fallback, sing-box errors, races, User-Agent подписок.
3. Батарея/perf: WorkManager, probe intervals **из кода в diff**, O(n) списки, `SubscriptionAutoUpdate` без сети (RU-OPTIMIZATION).
4. Maintainability: стиль CONTRIBUTING, KMP common vs platform.
5. Android/Desktop: lifecycle, `BaseService`, headless desktop.
6. RU: presets merge, `isProtectedBuiltinRule`, `RouteQuickProfile`, `values`+`values-ru`, WL+`ConfigBuilder`.
7. Battery audit — только если diff трогает bg/simplemode/обновления (таблица).
8. Тесты: ≤5 предложений (`AutoServerSelector`, handoff, `ProfileManager` order, `ConfigBuilder`, `BuiltinRouteRuleGuard`).
9. Safe refactor: extract/константа, без смены public API; >20 строк → Suggestion.

## Формат ответа

### Summary
### Issues (Critical | Important | Nice-to-have | Suggestion)
### Оставить как есть
### Архитектура (≤5 буллетов, без радикальных изменений)

---
**Вход:** <diff / PR / файлы>
```

### Scope-строки (добавить в конец)

- `Scope: simple mode + Android bg only`
- `Scope: routing presets only`
- `Не предлагай патч-код — только риск и условие, когда правка нужна`
- `Отвечай на русском`

---

## B. Промпт: review всего проекта (по волнам)

**Зачем волны:** дешёвая модель не может честно прочитать 600+ `.kt` за раз. Один запуск = **одна волна** + опционально сводка. Полный audit = 6–7 последовательных чатов.

**Перед стартом** (пользователь или агент с доступом к репо): прочитать `AGENTS.md`, `docs/RU-OPTIMIZATION.md`, `CONTRIBUTING.md`.

### Что не ревьюить без явного запроса

- `**/build/**`, `**/.gradle/**`, generated Compose resources
- `plugin/**/jni/**`, `plugin/**/go/**` (кроме JNI bridge в `composeApp`)
- `library/**` (вендорные UI-библиотеки)
- Каждый `fmt/*/*Fmt.kt` по отдельности — только выборочно в волне 6 или по scope
- Темы Compose (`compose/theme/*`), однотипные `*SettingsScreen.kt` — не перечислять все

### Промпт (copy-paste) — старт волны 1

```markdown
Ты — senior Kotlin/KMP инженер. **Аудит репозитория Dahusim по волнам.** Только отчёт, без правок в код.

## Ограничения
- Не предлагай глобальный рефакторинг, смену DI, объединение модулей.
- Finding только если **прочитал** файл (укажи путь). Не галлюцинируй API.
- Лимиты на эту волну: Critical≤3, Important≤5, Nice≤5, Suggestion≤3.
- Неуверенность → Suggestion.
- Обязательно: «Оставить как есть» ≥2 пункта по прочитанному коду.

## Контекст Dahusim
KMP VPN (sing-box), RU: simple mode, AutoServerSelector, network handoff, routing presets, whitelist networks. Модули: composeApp, androidApp, buildSrc, plugin:*.

## Текущая волна: <N> — <название из таблицы ниже>

Прочитай **все** файлы волны (grep/list при необходимости). Для каждой подсистемы: 1–2 предложения «как устроено», затем issues.

### Чеклист волны (отметь OK или finding)
- Корректность логики и инварианты
- Edge: сеть, reconnect, race, отмена корутин
- Согласованность с RU-OPTIMIZATION (если применимо)
- Батарея/фон (если android bg / WorkManager)
- Тесты: что отсутствует в commonTest для этой волны (≤3 идеи)

## Формат ответа

### Summary волны
### Карта подсистемы (кратко, как работает)
### Issues (Critical | Important | Nice-to-have | Suggestion)
### Оставить как есть
### Тесты — пробелы (≤3)
### Следующая волна
Рекомендуемая волна N+1 одной строкой.

---
Репозиторий: <путь или @workspace>. Волна: <N>.
```

### Таблица волн

| Волна | Название | Что читать (приоритет Dahusim) |
|-------|----------|-------------------------------|
| **0** | Inventory | `settings.gradle.kts`, `composeApp/build.gradle.kts`, `androidApp/build.gradle.kts`, `di/Koin.kt`, `Constants.kt`, `README.md` — **без severity issues**, только схема модулей и entry points |
| **1** | Simple mode | `simplemode/**` (common + android + desktop), `bootstrap/WhitelistBuiltinBootstrap.kt`, `utils/SimpleModeLog*.kt`, `ui/simple/SimpleHomeScreen.kt` |
| **2** | Android VPN / handoff | `androidMain/.../bg/BaseService.kt`, `DefaultNetworkMonitor.kt`, `WhitelistNetworkRoutingState.kt`, `NetworkReachabilityProbe.kt`, `SimpleModeVpnCoordinator.kt`, `SimpleModeSessionHealth.kt`, `SimpleModeTunnelRestart.kt`, `UiActivityTracker.kt` |
| **3** | Routing RU | `ProfileManager.kt`, `WhitelistRuRouting.kt`, `BuiltinRouteRuleGuard.kt`, `ConfigBuilder.kt` (routing parts), `SingBoxOptionsUtil.kt`, `RouteScreen.kt`, `RouteScreenViewModel.kt`, `RouteSettingsViewModel.kt`, `SettingsScreen.kt` (route quick profile) |
| **4** | Servers & subscriptions | `AutoServerSelector.kt`, `AutoServerSelectorProbePolicy.kt`, `group/**`, `bg/SubscriptionUpdater.kt`, `SubscriptionAutoUpdate.kt`, `SubscriptionCatalog*`, `ktx/Nets.kt` |
| **5** | Config & tunnel core | `fmt/ConfigBuilder.kt`, `bg/proto/**`, `libcore/**`, `repository/**`, `GuardedProcessPool.kt`, `Executable.kt` |
| **6** | Desktop & updates | `desktopMain/**` (bg, repository, simplemode), `update/**`, `androidApp/**` (shell only) |
| **7** | Сводка + тесты | Пройти findings волн 1–6; `commonTest/**` — матрица «модуль → есть тест / нет»; ≤10 приоритетных тестов на весь проект; Performance & Battery таблица для bg/simplemode |

### Промпт — финальная сводка (после волн 1–6)

```markdown
Собери **единый отчёт** по аудиту Dahusim из волн 1–6 (текст ниже — вставь findings).

Правила: дедупликация; глобальные лимиты Critical≤5, Important≤12 всего; не добавляй новых findings без цитаты из волн.
Структура:
### Executive summary (1 абзац)
### Top risks (≤5, по убыванию)
### Issues merged (Critical → Suggestion)
### Оставить как есть (архитектурные решения Dahusim)
### Test coverage matrix (волны 1–4)
### Battery / background audit (таблица)
### Roadmap (≤5 точечных задач, без «переписать проект»)

---
Findings волн:
<вставить>
```

### Scope для ускорения

- `Волны 1–3 only` — RU-критичный минимум
- `Skip fmt parsers`
- `Desktop skip`
- `Не предлагай патч-код`

### Как гонять в Cursor

1. Новый чат → промпт волны 1 → `@composeApp/src/.../simplemode`.
2. Следующий чат → «Продолжи волну 2» + промпт волны 2 + `@androidMain/.../bg`.
3. После волны 6 — промпт финальной сводки со вставленными findings.

---

## Локальный отчёт аудита

Полный проход по волнам (findings, условия Critical, roadmap) — **`docs/local/CODE_REVIEW_AUDIT.md`** (каталог в `.gitignore`). Обновляйте после цикла доработок; в git не коммитить без решения.

---

## Сравнение режимов

| | Diff/PR | Весь проект |
|--|---------|-------------|
| Вход | diff, PR | репо, по волнам |
| Глубина | изменённые файлы | приоритетные подсистемы |
| Сессий | 1 | 6–8 |
| Риск «сломать архитектуру» | низкий | средний без волн — **волны обязательны** |

---

## Почему так устроено

| Приём | Эффект |
|--------|--------|
| Запреты в начале | Меньше «перепиши всё» |
| Волны + лимиты | Меньше галлюцинаций на 600+ файлах |
| «Прочитал файл» | Evidence-based findings |
| «Оставить как есть» | Защита рабочих координаторов |
| Волна 0 без issues | Карта без ложных Critical |
| Финальная сводка | Один документ для backlog |
