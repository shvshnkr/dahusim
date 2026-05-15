# Linux desktop CI

Linux desktop test channel: [`.github/workflows/desktop-linux-test.yml`](../.github/workflows/desktop-linux-test.yml).

## Что делает workflow

- Триггер: только ручной `workflow_dispatch`.
- Runner: `ubuntu-latest`, `DESKTOP_TARGET=linux/amd64`.
- Сборка `libcore`, затем `make desktop_package_linux` → deb, rpm, pacman, uber JAR.
- Публикация: GitHub pre-release с тегом `linux-desktop-linux-amd64-<run_id>`.
- Smoke: [`buildScript/ci/smoke_linux_desktop_packages.sh`](../buildScript/ci/smoke_linux_desktop_packages.sh).

## Java runtime (deb / rpm / pacman)

- Минимум: **Java 21** (bytecode и API).
- CI: workflow [**JDK matrix**](../.github/workflows/jdk-matrix-test.yml) гоняет `composeApp:desktopTest` на Temurin **21–26** (Xvfb).
- **deb** (`release/linux/desktop/deb.control`): `Depends` перечисляет альтернативы `javaNN-runtime` и `openjdk-NN-jre` (полный JRE с AWT), **без** `*-jre-headless`: headless-пакет не содержит `libawt_xawt.so` и ломает запуск окна (`UnsatisfiedLinkError`).
- **rpm**: `Requires: java >= 21`.
- **pacman**: `depend = java-runtime>=21`.

На старых дистрибутивах в репозитории может быть только OpenJDK 21 — установка deb по-прежнему потянет его. Пакеты 24–26 в `Depends` не мешают: apt выбирает первую доступную ветку `|`.

## Если `softprops/action-gh-release` падает с `403`

См. [DESKTOP_WINDOWS_CI.md](./DESKTOP_WINDOWS_CI.md) (тот же `GH_RELEASE_TOKEN` / workflow permissions).
