---
description: Записать AI/cursorworklog.md через buildScript/worklog.sh — итог слайса/сессии, findings, карта (maps_updated/map_sync). Вызывается в конце сессии или по просьбе «запиши ворклог».
mode: subagent
---
Тонкий агент на канон. Прочитай скилл `.agents/skills/worklog/SKILL.md` и выполни его.

ВАЖНО: запись только через `bash buildScript/worklog.sh <файл-с-блоком>` (сам делает бэкап + append + проверку роста). НИКОГДА не писать в `AI/cursorworklog.md` напрямую (Write/Edit) — была потеря всей истории из-за ручной перезаписи.

Контекст: `AI/README.md` (формат); шаблон блока «Промпт для следующего агента» — в `AI/README.md` / `AI/cursorworklog.md`.