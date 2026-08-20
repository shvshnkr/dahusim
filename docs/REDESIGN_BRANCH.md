# Ветка redesign (экспериментальный UI)

Долгоживущая ветка `redesign` — только Compose/навигация/тема. Фичи и багфиксы коммитятся в `main`, затем периодически мержатся `main → redesign`.

## Версии

| Поле | main | redesign |
|------|------|----------|
| `VERSION_NAME` | `1.2.0-alpha.N` | то же (из merge) |
| `VERSION_NAME_SUFFIX` | — | `redesign` |
| Эффективное имя | `1.2.0-alpha.N` | `1.2.0-alpha.N-redesign` |
| `VERSION_CODE` | bump при shippable | **не bump'ить** на UI-коммитах; при merge брать из `main` |

Суффикс задаётся в [`husi.properties`](../husi.properties); Gradle склеивает через `effectiveVersionName()` в [`buildSrc`](../buildSrc/src/main/kotlin/Helpers.kt).

## CI на push в `redesign`

- Те же матрицы, что на `main`: JDK, stability, network/integration scenarios, Android instrumented smoke.
- Pre-release: **только Android** ([`all-platforms-build.yml`](../.github/workflows/all-platforms-build.yml)).
- Тег релиза: `android-redesign-<run_id>` (не `android-<run_id>`).
- Скачать: [GitHub Releases — redesign](https://github.com/shvshnkr/dahusim/releases?q=android-redesign+prerelease%3Atrue).

## App-update

- Канал [`app-update.json`](APP_UPDATE.md) публикуется через **Promote app update channel** с веток `main` **или `redesign`**
  (workflow разрешает оба ref; promote с `redesign` автоматически резолвит успешный раунд и тег `android-redesign-<run_id>`
  на той же ветке — linux/windows на redesign не строятся и в канал не попадают).
- Пока едем на redesign: promote запускается с redesign, канал ведёт пользователей на redesign-сборки
  (`versionCode` на redesign выше main — оффер приходит корректно).
- Promote workflow отклоняет запуск не с `refs/heads/main` / `refs/heads/redesign`.
- Команды: `bash buildScript/ci/gh-workflow.sh promote --ref redesign --from-changelog --dry-run` (dry-run перед публикацией),
  без `--dry-run` — публикация канала.

## Локальные команды

```bash
# Сборка redesign на GitHub (Android-only при dispatch с ref redesign)
gh workflow run all-platforms-build.yml --repo shvshnkr/dahusim --ref redesign

# Или через wrapper (main по умолчанию)
bash buildScript/ci/gh-workflow.sh build --ref redesign --android --wait

# Promote — с ветки redesign (канал ведёт на redesign-сборку)
bash buildScript/ci/gh-workflow.sh promote --ref redesign --from-changelog
# Превью без публикации:
bash buildScript/ci/gh-workflow.sh promote --ref redesign --from-changelog --dry-run
```

## Merge `main → redesign`

1. `git checkout redesign && git merge main`
2. При конфликте в `husi.properties`: взять `VERSION_NAME` и `VERSION_CODE` из `main`, сохранить `VERSION_NAME_SUFFIX=redesign`.
3. Push: `git push github redesign`.
