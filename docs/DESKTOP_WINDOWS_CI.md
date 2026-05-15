# Windows desktop CI

Windows desktop test channel реализован в [`.github/workflows/desktop-windows-test.yml`](../.github/workflows/desktop-windows-test.yml).

## Что делает workflow

- Триггер: только ручной `workflow_dispatch`.
- Runner: `ubuntu-latest`.
- Сборка `libcore` для `windows/amd64`: `./run lib core --desktop --desktoptargets windows/amd64`.
- Упаковка: `make desktop_package_windows DESKTOP_TARGET=windows/amd64`.
- Форматы: `zip` (portable), `-installer.exe` (NSIS), и uber JAR.
- Опционально на странице установщика: скачивание MSI **Eclipse Temurin 21** через `NSISdl` и установка **только при явной галочке**, **`msiexec /qn`** (без изменения `JAVA_HOME`, если пользователь не выбрал опцию).
- Публикация: отдельный GitHub pre-release с тегом `windows-desktop-windows-amd64-<run_id>`.
- В summary workflow выводится ссылка на созданный release (скачивание через блок Assets).
- Автоочистка старых тестовых Windows pre-release: хранится последние 15.

## При синке с upstream Husi

- **Zig:** в workflow зафиксирована версия Zig (см. `setup-zig` в `.github/workflows/desktop-windows-test.yml` и зеркально Linux). После обновления `launcher/` в upstream сверить с [README.md](../README.md) (раздел Desktop: *zig 0.15*) и при смене минимальной версии — поднять шаг CI и README в одном коммите.
- **Кодировка UI на Windows:** upstream обсуждает «квадратики» в трее/тостах ([husi#79](https://codeberg.org/xchacha20-poly1305/husi/issues/79), [husi#81](https://codeberg.org/xchacha20-poly1305/husi/issues/81)); возможны дефолтные JVM-опции в `release/.../desktop-java-opts.conf` или аналог. После мержа сравнить дефолтные опции Windows-упаковки с upstream `release/linux/desktop/desktop-java-opts.conf` / Windows-веткой.
- **Планировщик задач Windows:** при изменениях в `DesktopTaskScheduler.kt` проверить формат даты для `schtasks` (upstream [husi#91](https://codeberg.org/xchacha20-poly1305/husi/issues/91)); в текущем коде дата задаётся паттерном `MM/dd/yyyy`.
