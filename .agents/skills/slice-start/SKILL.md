---
name: slice-start
description: Запуск автономного слайса DaiHusim — из AI/plans/fix-slices.md или fix-backlog.md → одна задача, verify-гейты (desktopTest локально, CI на GitHub Actions). Вызывай для слайса из плана или по просьбе «следующий слайс».
---

# Запуск слайса

Выполни строго по порядку:

1. Прочитай `AI/plans/fix-slices.md` (актуальный план) или `AI/plans/fix-backlog.md` — выбери следующий слайс (`S*` / `R*` / `#N`).
2. Прочитай `AGENTS.md` → **Task router** под задачу слайса и `AI/README.md` (карта).
3. Читай код точечно: `symptoms-index.toml` (если симптом) → L1-hot TOML → `.kt` из entry. Не repo-wide explore.
4. Реализуй **только** выбранный слайс; не выходи за его scope.
5. **Verify (гейты):**
   - Локально: unit-тесты `ANDROID_HOME=C:/Android/sdk ABOUT_LIBRARIES_OFFLINE=true ./gradlew :composeApp:desktopTest --tests "<ClassName>"` (из Git Bash/WSL; в PowerShell — `\gradlew.bat`).
   - CI-гейты (`featureJourneyTest`, `fieldLogScenarioTest`, android-сборки) — только `bash buildScript/ci/gh-workflow.sh build`; не просить manual smoke.
6. После правок источников — `VERSION_CODE++` в `husi.properties`.
7. Закрой слайс: append `AI/cursorworklog.md` (скилл `worklog`), обнови план если нужно.

## Правила

- Слайс = одна задача; не «набрасывать» код вне слайса.
- Root cause first — без speculative guards и log-only фиксов.
- Не усложнять архитектуру вразрез с `invariants` в `project-map.toml` и `docs/BS_CS_NETWORK.md`.
- Инструменты: доступны WSL, Git Bash, PowerShell, go, java, python — выбирать оптимальный под задачу.
- **Долгие операции (>30 с: gradle-сборки/тесты, CI-запуски, диагностики) — только через `bash buildScript/long-run.sh start <name> -- <cmd>` + `status`/`log`/`stop`** (канон `docs/SCRIPTING_POLICY.md`), даже в слайсах. Не ждать молча в одном вызове, не `nohup &` напрямую.
- Команда завершилась — `status` покажет «НЕ запущен», результат — из `artifacts/<name>.log`; ошибку разбирать по полному логу.

$ARGUMENTS — override slice id (если пользователь указал конкретный слайс).