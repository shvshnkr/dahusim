# Windows desktop CI

Windows desktop test channel реализован в [`.github/workflows/desktop-windows-test.yml`](../.github/workflows/desktop-windows-test.yml).

## Что делает workflow

- Триггер: только ручной `workflow_dispatch`.
- Runner: `ubuntu-latest`.
- Перед `libcore`: скачивается **Windows** Temurin 21 x64 (только для `include`/`include/win32`), потому что хостовый `JAVA_HOME` — Linux JDK, а `anja` для `windows/*` требует `win32/jni_md.h` (см. `buildScript/ci/prepare_windows_jdk_jni_include.sh`).
- Сборка `libcore` для `windows/amd64`: `./run lib core --desktop --desktoptargets windows/amd64 --jniinclude "$JNI_INCLUDE"` (переменная задаётся скриптом выше).
- Упаковка: `make desktop_package_windows DESKTOP_TARGET=windows/amd64`.
- Форматы: `zip` (portable), `-installer.exe` (NSIS), и uber JAR.
- Опционально на странице установщика: скачивание MSI **Eclipse Temurin 21** через плагин **NSISdl** (`NSISdl::download`, без отдельного `NSISdl.nsh` — заголовок не входит в апстрим NSIS 3.10) и установка **только при явной галочке**, **`msiexec /qn`** (без изменения `JAVA_HOME`, если пользователь не выбрал опцию). URL закреплены в [`buildScript/temurin21-pinned.urls`](../buildScript/temurin21-pinned.urls) (зеркало [adoptium/temurin21-binaries](https://github.com/adoptium/temurin21-binaries) на GitHub, не adoptium.net). Перед JNI-скачиванием CI гоняет [`buildScript/ci/verify_temurin21_urls.sh`](../buildScript/ci/verify_temurin21_urls.sh).
- Публикация: отдельный GitHub pre-release с тегом `windows-desktop-windows-amd64-<run_id>`.
- В summary workflow выводится ссылка на созданный release (скачивание через блок Assets).
- Автоочистка старых тестовых Windows pre-release: хранится последние 15.

## Если `softprops/action-gh-release` падает с `403 Resource not accessible by integration`

1. В репозитории GitHub: **Settings → Actions → General → Workflow permissions** — включить **Read and write permissions** (и при необходимости «Allow GitHub Actions to create and approve pull requests», если политика org это допускает).
2. Если на уровне **организации** для `GITHUB_TOKEN` жёстко задан только read — в **Settings → Secrets and variables → Actions** добавить классический PAT с правом **`repo`** под именем **`GH_RELEASE_TOKEN`**; workflow уже передаёт в релиз и в `gh release delete` выражение `secrets.GH_RELEASE_TOKEN || github.token`.

## При синке с upstream Husi

- **Zig:** в workflow зафиксирована версия Zig (см. `setup-zig` в `.github/workflows/desktop-windows-test.yml` и зеркально Linux). После обновления `launcher/` в upstream сверить с [README.md](../README.md) (раздел Desktop: *zig 0.15*) и при смене минимальной версии — поднять шаг CI и README в одном коммите.
- **Кодировка UI на Windows:** upstream обсуждает «квадратики» в трее/тостах ([husi#79](https://codeberg.org/xchacha20-poly1305/husi/issues/79), [husi#81](https://codeberg.org/xchacha20-poly1305/husi/issues/81)); возможны дефолтные JVM-опции в `release/.../desktop-java-opts.conf` или аналог. После мержа сравнить дефолтные опции Windows-упаковки с upstream `release/linux/desktop/desktop-java-opts.conf` / Windows-веткой.
- **Планировщик задач Windows:** при изменениях в `DesktopTaskScheduler.kt` проверить формат даты для `schtasks` (upstream [husi#91](https://codeberg.org/xchacha20-poly1305/husi/issues/91)); в текущем коде дата задаётся паттерном `MM/dd/yyyy`.
