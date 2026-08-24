---
name: worklog
description: Закрытие сессии DaiHusim — append AI/cursorworklog.md (maps_updated/map_sync) + блок «Промпт для следующего агента». Используй в конце сессии, после слайса, или по просьбе «запиши ворклог».
---

# Worklog — закрытие сессии

Прочитай `AI/README.md` (формат и правила синхронизации карты).

**Единственный способ записи — скрипт `buildScript/worklog.sh`.** Он сам делает
бэкап (timestamped + rolling `.bak`), дописывает в конец и проверяет, что файл
только вырос. Не редактируй `AI/cursorworklog.md` вручную (Write/Edit/IDE) —
именно ручная перезапись уже один раз стёрла всю историю.

1. Собери текст записи (markdown-блок, см. формат ниже) в файл, например
   `$TEMP/wl-entry.md` (или `buildScript/wl-entry.md`, потом удали).
2. `bash buildScript/worklog.sh $TEMP/wl-entry.md`
3. Проверь хвост: `tail -5 AI/cursorworklog.md`.
4. Удали временный файл.

Формат записи:

```markdown
## YYYY-MM-DD — <слайс id или тема> (<short title>)

task: <что делали / симптом>
maps_updated: <paths или none>
map_sync: L1-hot <подсистема> | none

files:
- <изменённые файлы>
- husi.properties VERSION_CODE <new>
```

Добавь блок **«Промпт для следующего агента»**:

```markdown
## Промпт для следующего агента

Контекст: DaiHusim, workspace C:\Users\user\DaiHusim
Прочитай: AGENTS.md → Task router; AI/README.md; AI/cursorworklog.md (последний)

Задача: <ключевая следующая задача>
Сделано: <что сделано в этой сессии>
Осталось: <что не доделано>
Блокер: <есть/нет>
Не делать: <что нельзя трогать>
```

## Если слайс закрыт — убедись что обновлены

- `AI/plans/fix-slices.md` или `fix-backlog.md` — статус слайса
- `AI/cursorworklog.md` — запись сессии
- при hot-path change (autoselect/WL/health/adapt): L1-hot TOML + при новом H-tag — `symptoms-index.toml`

## Правила

- **APPEND-ONLY, только через `bash buildScript/worklog.sh <file>`.** НИКОГДА не
  перезаписывать `AI/cursorworklog.md` целиком (никаких `Write` всего файла,
  `Set-Content`, `>` / `>>` в shell без нужды). Скрипт сам делает бэкап
  (`AI/backups/cursorworklog-<ts>.md` + rolling `.bak`), дописывает и проверяет
  рост строк. Перезапись всего файла стирает историю сессий — это потеряно
  безвозвратно, если нет бэкапа.
- **Проверка после записи:** `wc -l` должен вырасти (скрипт делает сам); визуально
  — `tail -5 AI/cursorworklog.md` показывает новую запись.
- Восстановление при утере: `AI/cursorworklog.md.bak` — rolling-бэкап;
  `AI/backups/` — timestamped; глубокое восстановление — транскрипты сессий
  opencode (`~/.local/share/opencode/opencode.db`, таблица `part`, read-вызовы
  `cursorworklog.md` содержат полный текст).
- Багфикс без изменения логики: `maps_updated: none`, `map_sync: none`.
- Секреты и длинные логи — не в worklog; длинные логи — путь к файлу, не paste.
- В чат — краткий отчёт + «Следующий слайс: <id>» или «план исчерпан».

$ARGUMENTS — дополнение к notes, если передано.