# Сборка APK (локально)

Инструкции ниже проверялись **только на Windows 10** с **JDK 21 (Eclipse Adoptium)** и Gradle из проекта. На Linux и macOS команды обычно те же, но пути, кавычки и окружение отличаются — **мы их здесь не проверяли**.

**CI (GitHub):** при пуше, где меняется не только документация, в [`.github/workflows/build.yml`](.github/workflows/build.yml) собирается Play debug APK (arm64) и публикуется pre-release. **APK** лежат во **вложениях (Assets)** к релизу: **[Releases / Latest](https://github.com/shvshnkr/dahusim/releases/latest)** — скачивайте файлы оттуда, не из клонирования репо. См. раздел в [README](README.md).

## Как выбирается ABI

В `buildSrc` функция `requireTargetAbi()` смотрит на **ровно одну** задачу из командной строки Gradle. В имени задачи (без учёта регистра) ищутся подстроки **в таком порядке**:

| Подстрока в имени задачи | Один ABI в APK |
|--------------------------|----------------|
| `arm64` | `arm64-v8a` |
| `arm` (если уже не сработало `arm64`) | `armeabi-v7a` |
| `x64` | `x86_64` |
| `x86` | `x86` |

Если ни одно условие не выполнено (или в команде **несколько** задач), в сплит попадают **все четыре** ABI: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`.

Поэтому:

- для **ускорения** на одном устройстве вызывайте **одну** задачу с нужным маркером (например `assemblePlayDebugArm64`);
- для **полного набора** APK по ABI — задачу **без** этих маркеров (см. ниже).

## Требования

- **JDK 21** (например Adoptium). Задайте `JAVA_HOME` на корень JDK (не на `bin`):

  ```powershell
  $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
  $env:Path = "$env:JAVA_HOME\bin;$env:Path"
  ```

- В корне репозитория: `gradlew.bat` (Windows) или `./gradlew` (Unix).

## Play — все архитектуры (четыре APK)

Из **корня** репозитория (удобные алиасы):

```powershell
.\gradlew assemblePlayDebugAllAbi --no-daemon
.\gradlew assemblePlayReleaseAllAbi --no-daemon
```

Эквивалент напрямую по модулю (тоже **все** ABI, одна задача в CLI):

```powershell
.\gradlew :androidApp:assemblePlayDebug --no-daemon
.\gradlew :androidApp:assemblePlayRelease --no-daemon
```

## Play — одна архитектура (быстрее)

Корневые задачи (см. `build.gradle.kts`):

| ABI | Debug | Release |
|-----|--------|---------|
| arm64-v8a | `assemblePlayDebugArm64` | `assemblePlayReleaseArm64` |
| armeabi-v7a | `assemblePlayDebugArm` | `assemblePlayReleaseArm` |
| x86_64 | `assemblePlayDebugX64` | `assemblePlayReleaseX64` |
| x86 | `assemblePlayDebugX86` | `assemblePlayReleaseX86` |

Пример:

```powershell
.\gradlew assemblePlayDebugArm64 --no-daemon
```

**Не смешивайте** в одной команде несколько таких задач — тогда сработает режим «все ABI» для конфигурации.

## Foss — все архитектуры

```powershell
.\gradlew assembleFossDebugAllAbi --no-daemon
.\gradlew assembleFossReleaseAllAbi --no-daemon
```

Или:

```powershell
.\gradlew :androidApp:assembleFossDebug --no-daemon
.\gradlew :androidApp:assembleFossRelease --no-daemon
```

## Foss — одна архитектура

| ABI | Debug | Release |
|-----|--------|---------|
| arm64-v8a | `assembleFossDebugArm64` | `assembleFossReleaseArm64` |
| armeabi-v7a | `assembleFossDebugArm` | `assembleFossReleaseArm` |
| x86_64 | `assembleFossDebugX64` | `assembleFossReleaseX64` |
| x86 | `assembleFossDebugX86` | `assembleFossReleaseX86` |

## Всё сразу (foss + play, все ABI)

Дольше всего — оба flavor и все ABI:

```powershell
.\gradlew :androidApp:assembleDebug --no-daemon
.\gradlew :androidApp:assembleRelease --no-daemon
```

Для локальной установки на один телефон это обычно избыточно.

## Где лежат APK

После сборки:

- Play debug: `androidApp/build/outputs/apk/play/debug/`
- Play release: `androidApp/build/outputs/apk/play/release/`
- Foss: `androidApp/build/outputs/apk/foss/debug/` и `.../foss/release/`

Имена файлов зависят от `VERSION_NAME` в `husi.properties` и суффикса ABI (например `...-play-arm64-v8a-debug.apk`).

## Release и подпись

Для подписи release нужны параметры в `local.properties` (см. `buildSrc`, `setupAppCommon()`): `KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS` и файл `release.keystore` в корне. Без этого сборка release может не пройти.

## Чистая сборка и Windows

Если `:androidApp:clean` падает с `Unable to delete ... apk`, чаще всего файл открыт в Проводнике, антивирусе или установщике — закройте доступ к `androidApp\build\outputs` и повторите.

## Версия приложения

`VERSION_NAME` и `VERSION_CODE` задаются в `husi.properties` в корне репозитория.

## Откат после правок RU optimization (git)

Перед доработками по замечаниям из upstream был создан тег:

`checkpoint/before-ru-refinements`

Откат всего дерева к тому коммиту:

```powershell
git reset --hard checkpoint/before-ru-refinements
```

Просмотр коммита тега: `git show checkpoint/before-ru-refinements`.
