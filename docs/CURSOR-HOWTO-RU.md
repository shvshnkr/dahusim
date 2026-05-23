# Cursor: краткий how-to для Dahusim

## Контекст и окна агента

- **Новый чат Agent/Composer** — на каждую крупную задачу (рефакторинг WL, новый баг, смена архитектуры). Длинная переписка съедает лимит контекста; модель «забывает» ранние решения и путает ветки.
- **Plan mode** — перед большим diff: согласовать scope, не трогать файлы до подтверждения плана.
- **@‑файлы** — прикрепляйте 3–8 ключевых файлов (`@AutoServerSelector.kt`), не весь репозиторий.
- **Фоновый explore** — для обзора кодовой базы отдельный агент; основной чат — для правок.

## Проект

- Правила: [`AGENTS.md`](../AGENTS.md), стиль: [`CONTRIBUTING.md`](../CONTRIBUTING.md).
- После правок Kotlin: **`VERSION_CODE++`** в [`husi.properties`](../husi.properties).
- APK через Telegram/messenger: ещё **`VERSION_NAME`** (иначе одинаковое имя файла).
- Коммиты — только по явной просьбе. Не коммитить `libcore-desktop-*.jar` и прочие артефакты сборки.

## Запрос к агенту (что писать)

1. Симптом (не коннектится / fallback / только builtin).
2. Сеть: **WL** (Google недоступен, Dzen/зонды OK) или **open**.
3. Фрагмент лога с тегами `H24`, `H38`, `H14`, `H17`, `H4`, `H1`, `H37`.
4. Ограничение: «не наращивать подписки — исправить отбор в пуле».

## Логи Dahusim (не папка Telegram)

Лог пишется в кэш приложения:

`{cacheDir}/simple-mode/simple_mode_app.log`

На Android обычно: `/data/user/0/fr.husi/cache/simple-mode/`.

**«Отправить логи»** в simple mode копирует файл и открывает share — Telegram только как цель отправки, не как хранилище.

Полезные grep-якоря:

| Тег | Смысл |
|-----|--------|
| `H0` | Старт сессии, `build=` / `code=` |
| `H24` | Сборка пула: `wlNet`, `subsWlMarked`, `pool` |
| `H38` | Сужение WL-пула: `before` / `after` |
| `H14` | TCP probe |
| `H17` | URL probe |
| `H4` | Очередь fallback, `head=` |
| `H1` | Переключение fallback |
| `H37` | Post-connect health |
| `H35` | Jail / probe2k |

Подробнее: [`RU-OPTIMIZATION.md`](RU-OPTIMIZATION.md) (раздел simple-mode).
