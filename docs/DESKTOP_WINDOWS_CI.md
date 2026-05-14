# Windows desktop CI (фаза 2)

Linux desktop test channel реализован в [`.github/workflows/desktop-linux-test.yml`](../.github/workflows/desktop-linux-test.yml). Windows (`windows/amd64`, при необходимости `windows/arm64`) вынесен во вторую фазу из‑за окружения: сборка и [`release/windows/package.sh`](../release/windows/package.sh) ориентированы на **Windows или MSYS2** с Zig, Go, Gradle и (для naive) checkout **cronet-go**, по аналогии с Linux workflow.

## План включения

1. **Runner:** `windows-latest` (или self-hosted с MSYS2 + зависимостями из README).
2. **Libcore:** `./run lib core --desktop --desktoptargets windows/amd64` с `CRONET_GO_ROOT` и кэшем toolchain (см. `oldgithub/.github/workflows/release.yml`).
3. **Uber JAR + установщик:** `make desktop_package_windows DESKTOP_TARGET=windows/amd64` из корня в MSYS2 / Git Bash, либо эквивалентные шаги из `Makefile`.
4. **Публикация:** только артефакты Actions или отдельный prerelease-тег (например `windows-desktop-test-<run_id>`), **без** смешения с rolling APK и без обязательного общего релиза.

## Заготовка workflow

Файл [`.github/workflows/desktop-windows-test.yml`](../.github/workflows/desktop-windows-test.yml) оставлен как **ручной dispatch** и точка входа для дальнейшей доработки; пока не выполняет полную сборку.
