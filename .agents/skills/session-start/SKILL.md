---
name: session-start
description: Старт сессии DaiHusim — AGENTS.md task router + AI/project-map.toml + последний AI/cursorworklog.md → одна цель. Вызывай в начале сессии или по просьбе «начнём».
---

# Старт сессии

Выполни по порядку:

1. Прочитай `AGENTS.md` — выбери строку **Task router** под задачу пользователя.
2. Прочитай `AI/README.md` и `AI/project-map.toml` — нужные секции (modules/packages/entry) для ориентации.
3. Прочитай последнюю запись `AI/cursorworklog.md` — что было в прошлой сессии (handoff между IDE).
4. Сформулируй **одну цель** сессии и подтверди её пользователю, прежде чем менять код.

## Полезно знать

- Каждая сессия = **одна цель** (bugfix/feature/journey). Длинный список задач — в `AI/plans/fix-backlog.md` / `fix-slices.md`.
- Карта проекта: L0 `project-map.toml`, при симптоме — `symptoms-index.toml` → L1-hot TOML → `.kt` из entry.
- CI-гейты (`featureJourneyTest`, `fieldLogScenarioTest`, android-сборки) — только GitHub Actions через `bash buildScript/ci/gh-workflow.sh build`; локально — unit-тесты `desktopTest` по классу.
- После правок Kotlin/Gradle/resources — инкремент `VERSION_CODE` в `husi.properties`.
- Коммиты — только по явной просьбе.

$ARGUMENTS — тема/задача сессии, если передана пользователем.