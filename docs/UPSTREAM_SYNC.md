# Синхронизация с апстримом (ручной цикл)

Цель — предсказуемо подтягивать `upstream` (ветка по умолчанию **`dev`**) в форк, не ломая грязное дерево и не смешивая это с `make update` (сабмодули).

## Ремоуты

Ожидается:

- **`upstream`** — основной апстрим (husi на Codeberg).
- **`origin`** — ваш форк (push).
- **`github`** — зеркало на GitHub (push).

Имена можно переопределить переменными `UPSTREAM_REMOTE`, `ORIGIN_REMOTE`, `GITHUB_REMOTE`.

## Команды

Статус и выборка (fetch + отчёт `ahead/behind`, `merge-base`, первые коммиты left-right):

```bash
make sync_upstream
# или
./run lib sync_upstream
```

То же, что `status` (по умолчанию).

Интеграционная ветка + **merge** `upstream/dev` (нужно чистое дерево):

```bash
make sync_upstream SYNC_UPSTREAM_ARGS=merge
# или
./run lib sync_upstream merge
```

Создаётся ветка `sync/upstream-YYYYMMDD-HHMMSS` от текущего `HEAD`, затем merge из `upstream/dev` (если вы ещё не содержите все коммиты апстрима).

**Rebase** на `upstream/dev` (чистое дерево, линейная история, конфликты правятся вручную):

```bash
./run lib sync_upstream rebase
```

Ветка апстрима по умолчанию: `UPSTREAM_REF=dev` → полный ref `upstream/dev`. Для другой ветки:

```bash
UPSTREAM_REF=main ./run lib sync_upstream status
```

## Рекомендуемый порядок (первый controlled sync)

1. Закоммитьте или спрячьте локальные правки; убедитесь, что ветка осмысленна (например `feature/...`).
2. `./run lib sync_upstream` — посмотреть расхождение с `upstream/dev`.
3. `./run lib sync_upstream merge` или `rebase` — получить ветку `sync/upstream-…`.
4. Минимальный gate перед переносом в рабочую ветку:
   - `./gradlew :composeApp:compileAndroidMain` (или ваша стандартная Android-сборка);
   - Linux desktop smoke (локально при возможности): `make libcore_desktop DESKTOP_TARGETS=linux/amd64`, затем `make desktop_package_linux DESKTOP_TARGET=linux/amd64` и `bash buildScript/ci/smoke_linux_desktop_packages.sh`.
5. После успешного gate — cherry-pick/merge интеграционной ветки в рабочую, затем `git push origin` и при необходимости `git push github`.

## Не путать с

- **`make update`** — обновление сабмодулей (`buildScript/lib/update.sh`), не rebase/merge апстрима git.
