# Windows desktop CI

Windows desktop test channel реализован в [`.github/workflows/desktop-windows-test.yml`](../.github/workflows/desktop-windows-test.yml).

## Что делает workflow

- Триггер: только ручной `workflow_dispatch`.
- Runner: `ubuntu-latest`.
- Сборка `libcore` для `windows/amd64`: `./run lib core --desktop --desktoptargets windows/amd64`.
- Упаковка: `make desktop_package_windows DESKTOP_TARGET=windows/amd64`.
- Форматы: `zip` (portable), `-installer.exe` (NSIS), и uber JAR.
- Публикация: отдельный GitHub pre-release с тегом `windows-desktop-windows-amd64-<run_id>`.
- В summary workflow выводится ссылка на созданный release (скачивание через блок Assets).
- Автоочистка старых тестовых Windows pre-release: хранится последние 15.
