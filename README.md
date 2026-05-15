# Dahusim

**Dahusim** — форк Android-клиента **[husi](https://codeberg.org/xchacha20-poly1305/husi)** (Husi, sing-box и связанный UI): тот же класс приложений, но с **простым режимом**, **автовыбором точки из встроенного набора открытых («фри») серверов** и **готовыми маршрутами для обхода цензуры** с **оптимизацией под РФ** (геосайты/геоIP, сканер приложений, пресеты — см. [docs/RU-OPTIMIZATION.md](docs/RU-OPTIMIZATION.md)). Исходный **husi** живёт на Codeberg и развивается там; **этот репозиторий** — линия сборки Dahusim и поведения «из коробки».

*English (short).* **Dahusim** is an **Android fork of [husi](https://codeberg.org/xchacha20-poly1305/husi)** with a **simple UI**, **auto-pick among bundled public/community endpoints**, and **RU-oriented routing presets** for censorship-heavy networks (see [docs/RU-OPTIMIZATION.md](docs/RU-OPTIMIZATION.md)). Upstream Husi is maintained on Codeberg; **this repo** is where this flavor of defaults and releases is maintained.

## О сборках, «КВН» и рисках

**Где взять готовый APK (GitHub).** Собранные установщики публикуются **на GitHub** в виде вложений (assets) к pre-release. **Скачать последнюю версию** = открыть страницу **последнего релиза** и взять файлы из блока *Assets*, не из исходников. Прямая ссылка: **[Releases / Latest](https://github.com/shvshnkr/dahusim/releases/latest)**. В релизе **четыре** debug-APK по ABI: `dahusim_<версия>_play_debug_arm64_v8a.apk`, `..._armeabi_v7a.apk`, `..._x86_64.apk`, `..._x86.apk` (суффикс в имени — целевая архитектура; на устройстве обычно нужен **arm64**).

**Desktop (Linux / Windows) на GitHub.** Отдельные ручные pre-release собираются workflows **Linux desktop** и **Windows desktop** (вкладка Actions → выбрать workflow → *Run workflow*). Артефакты — в **Releases → соответствующий pre-release → Assets**. На компьютере пользователя нужен **JRE или JDK 21+** (в сборку **не** входит встроенный JRE); старая «Java 8» не подойдёт — см. обсуждение [husi#30](https://codeberg.org/xchacha20-poly1305/husi/issues/30). **Linux:** пакет `openjdk-*-jre-headless` **не** подходит для окна приложения (нет `libawt_xawt.so` → `UnsatisfiedLinkError`); нужен **`*-jre`** или **`*-jdk`** с графическим стеком, либо другой JDK 21+ с AWT. Под **WSL** нужны WSLg или корректный `DISPLAY`; **чёрное окно** иногда даёт связка Compose/Skia и графики WSL — обновите WSL/WSLg, поэкспериментируйте с `_JAVA_OPTIONS` (например уберите или поменяйте `-Dprism.order=sw`, если задавали вручную). На **Windows** лаунчер ищет подходящую Java 21+ в `%APPDATA%\husi\desktop-java-home.conf` (первая строка: каталог JDK или полный путь к `java.exe`/`javaw.exe`) **раньше**, чем `JAVA_HOME` и `PATH`, сканирует типовые каталоги (**включая `Program Files (x86)`** и переменную `ProgramFiles(x86)`), и при необходимости откроет страницу Temurin или диалог выбора файла. Если **`JAVA_HOME` указывает на Java 8/11/17/19**, этот путь **пропускается** (проверка версии), поиск продолжается — можно зафиксировать Java 21 только в `desktop-java-home.conf` или через диалог, **системный `JAVA_HOME` лаунчер не меняет**. Установщик NSIS ставит Temurin 21 **только при явной галочке** (тихий MSI `/qn`), без согласия не перебивает вашу среду.

**CI: матрица JDK.** Workflow **JDK matrix (composeApp desktop tests)** (Actions): один раз собирает `libcore` для `linux/amd64`, затем на **Temurin 21–26** выполняет `./gradlew :composeApp:exportLibraryDefinitions :composeApp:desktopTest` под **`xvfb-run`** (иначе на Linux CI возможен `java.awt.HeadlessException` у desktop JVM). Триггеры: **Run workflow** вручную и **push** в `main` при изменениях в `composeApp/`, `library/`, `libcore/`, `buildSrc/`, `gradle/`, корневых Gradle-файлах, `buildScript/`, `husi.properties` или в самом workflow; **не** стартует от правок только в `release/` (NSIS и т.д.), только markdown и т.п. Долгие прогоны не отменяются новым push в ту же ветку (`cancel-in-progress: false`).

**КВН вместо «VPN».** В текстах этого форка мы пишем **«КВН»** (шутливое, договорённое обозначение **вместо** привычного **«VPN»** — отсылка к известной аббревиатуре, **не** к шоу в первую очередь) так, чтобы **не** путать сборку с рекламой «официального VPN» и не обещать «сервис как в рекламе». Это **клиент** для **исследовательского и любительского** сценария, без гарантий, что это «именно VPN» в юридическом/маркетинговом смысле.

**Открытые сервера и автовыбор.** Возможен **автовыбор** (или полуавтоматический выбор) точки на **публичных, открытых серверах энтузиастов/сообщества** — в зависимости от логики клиента. Это **не** обязательно быстрее/безопаснее «ваш» сервер: вы сами оцениваете риск доверия к чужим площадкам и **соблюдаете законы** вашей страны.

**Исследование и риск.** Сборка даётся **as is**, без гарантий, без обещания соответствия нормам. Авторы и участники **не несут ответственности** за убытки, утечки, правовые и прочие последствия.

**Vibecode.** Значимая часть кода сделана в стиле **vibecoding** — быстрые итерации с нейроассистом (например Cursor) плюс ручные правки. Это **не** формальная ревизия и не аудит безопасности; исходники смотрите сами, как в неоценённом pull request.

*English (short).*: [GitHub Releases (latest)](https://github.com/shvshnkr/dahusim/releases/latest) ships **four** per-ABI debug APKs as **assets**; no warranty; “KVN” is a tongue-in-cheek stand-in for “VPN”; public/community endpoints and auto-pick are at your own risk. **Desktop** Linux/Windows builds are separate manual pre-releases and require **Java 21+** on the host (no bundled JRE). **Linux:** use a **full** JRE/JDK with AWT/X11 (`openjdk-*-jre`, not `*-jre-headless`), or you may hit `UnsatisfiedLinkError` for `libawt_xawt.so`. **WSL** needs WSLg or a proper `DISPLAY`; a **black window** is often a graphics-stack/Compose issue—try updating WSL/WSLg and tuning `_JAVA_OPTIONS` / `-Dprism.order`. **CI:** workflow **JDK matrix (composeApp desktop tests)** runs `desktopTest` on Temurin **21–26** under **Xvfb** (manual dispatch, or `push` to `main` when `composeApp/`, `library/`, `libcore/`, Gradle roots, `husi.properties`, etc. change — not packaging-only commits).

## Disclaimer (this repository)

**As is.** The software is provided without warranty of any kind, express or implied. The authors and contributors **disclaim all responsibility** for damages, losses, legal issues, or any consequences arising from use or misuse of this build. You use it entirely at your own risk; there is no commitment of fitness for any particular purpose.

**Research and education.** This application is provided **for research and study** of networking and client behaviour, not as a vetted product for operational or compliance-sensitive environments.

**In-app behaviour.** The client may use **automated or semi-automated selection of access points** (e.g. community- or operator-provided “enthusiast” endpoints) where the implementation supports it. You are responsible for the networks and services you use and for complying with local law.

**Vibecoding and AI assist.** A substantial part of this codebase is **vibecoding** — fast, iterative work with an AI pair-programmer (e.g. in Cursor) — in addition to manual edits. It is not a formally specified, audited, or security-certified product unless you perform your own review. Treat outputs as you would unreviewed code.

**Releases (GitHub).** Pre-built APKs (four ABI splits): **[latest release — Assets](https://github.com/shvshnkr/dahusim/releases/latest)** — names like `dahusim_<version>_play_debug_<abi>.apk`. Upstream **husi** is linked in the introduction above.

## 🗣️ Alert

In August 2025, Google [announced](https://developer.android.com/developer-verification) that as of September 2026, it will no longer be possible to develop apps for the Android platform without first registering centrally with Google. This registration will involve:

- Paying a fee to Google
- Agreeing to Google’s Terms and Conditions
- Providing government identification
- Uploading evidence of the developer’s private signing key
- Listing all current and future application identifiers

As a free software, husi will never submit to Google. Visit [Keep Android Open](https://keepandroidopen.org/) to defend the openness!

## 🛠️ Contribution

## 🧭 Guide

[CONTRIBUTING](./CONTRIBUTING.md)

### 📚 Localization

Is husi not in your language, or the translation is incorrect or incomplete? Get involved in the
translations on our [Weblate](https://hosted.weblate.org/engage/husi/).

[![Translation status](https://hosted.weblate.org/widgets/husi/-/horizontal-auto.svg)](https://hosted.weblate.org/engage/husi/)

### 🔨 Learn to Compilation

In Linux, you can build husi reproducibly for release version.

For this, you should use the same version of JDK, NDK as below. And Go version should as same
as [version.sh](./buildScript/init/version.sh).

#### 🧰 Get the Source Code

```shell
git clone https://codeberg.org/xchacha20-poly1305/husi.git --depth=1
cd husi/
./run lib source # Will help you to get submodules
```

#### ⚖️ libcore

Environment:

* These versions need to apply patch.

  <details>
    <summary>Unfold</summary>

  1.22.5: Apply [this patch](./libcore/patches/cgo_go1225.diff) to `${GOROOT}/src/runtime/cgocall.go`

  1.23.0-1.23.3: Apply [this patch](https://github.com/golang/go/commit/76a8409eb81eda553363783dcdd9d6224368ae0e.patch)
  to`${GOROOT}`. `make patch_go1230`

  1.23.4: Apply [this patch](https://github.com/golang/go/commit/59b7d40774b29bd1da1aa624f13233111aff4ad2.patch) to `$(GOROOT)`. `make patch_go1234`

  </details>

* Openjdk-21 (Later may OK, too.)

For Android:

```shell
make libcore_android
```

This will generate `composeApp/libs/libcore.aar`.

For desktop, build libcore for your host platform:

```shell
make libcore
```

This will generate `composeApp/libs/libcore-desktop-<host-platform>-<host-arch>.jar`.

Or for specific targets:

```shell
make libcore_desktop DESKTOP_TARGETS=linux/amd64,darwin/arm64
```

If desktop build needs an explicit JNI headers directory, pass `JNI_INCLUDE`
(the directory that contains `jni.h`, with a platform subdir such as `linux/` or `win32/`):

```shell
make libcore_desktop DESKTOP_TARGETS=linux/amd64 JNI_INCLUDE=/path/to/jni
```

On **Linux**, cross-building `windows/amd64` needs **Windows** JDK headers (host `JAVA_HOME` is not enough). CI uses `buildScript/ci/prepare_windows_jdk_jni_include.sh`; locally pass `--jniinclude` / `JNI_INCLUDE` to that JDK’s `include` path.

For Darwin targets on non-Darwin hosts, also pass the macOS SDK explicitly:

```shell
make libcore_desktop DESKTOP_TARGETS=darwin/arm64 JNI_INCLUDE=/path/to/jni DARWIN_SDK=/path/to/MacOSX.sdk
```

Common desktop targets:

* `linux/amd64`
* `linux/arm64`
* `darwin/amd64`
* `darwin/arm64`

For Linux desktop targets, the build includes `with_naive_outbound` and consults a
[`cronet-go`](https://github.com/sagernet/cronet-go) checkout via `build-naive env`. If `CRONET_GO_ROOT` is unset,
`libcore/build.sh` first checks `../../cronet-go`, then falls back to `$HOME/cronet-go`:

```shell
CRONET_GO_ROOT=/path/to/cronet-go make libcore
```

For Linux targets, `cronet-go` exports the naiveproxy cross-toolchain environment directly.
For Darwin targets on a Darwin host, `libcore/build.sh` keeps `with_naive_outbound` and derives `CC`/`CXX`/`CGO_*`
from the Chromium clang and hermetic Xcode toolchain inside the `cronet-go` checkout. If the Darwin SDK/linker tree
is missing, the desktop build fails immediately.
For Darwin targets on non-Darwin hosts, `libcore/build.sh` uses `zig cc` / `zig c++`, requires an explicit macOS SDK
path via `DARWIN_SDK` or `--darwinsdk`, exports the matching `CGO_*` sysroot/library flags, keeps
`with_naive_outbound`, and does not require a `cronet-go` checkout, so `zig` must be available in `PATH`.

Desktop Gradle builds select `composeApp/libs/libcore-desktop-<platform>-<arch>.jar` automatically from the current
`os.name` and `os.arch`.

You can override it explicitly:

```shell
./gradlew -p composeApp run -PdesktopTarget=linux/amd64
```

If the selected jar is missing, the build fails immediately.

If you run `libcore/build.sh` directly:

* `--android`: build Android only
* `--desktop`: build desktop only (default target: `host`)
* `--android --desktop`: build both
* `--jniinclude <path>`: pass JNI headers include path to desktop `anja bind -target=jvm`
* `--darwinsdk <path>`: pass a macOS SDK path for Darwin desktop targets on non-Darwin hosts
* no platform args: defaults to Android only

If anja is not in GOPATH, it will be automatically downloaded and compiled.

#### 🎀 Rename package name (optional)

If you don't want to use the same package name, you can run `./run rename target_name`.

#### 🎁 APK

Environment:

* jdk-21
* ndk 29.0.14206865

If the environment variables `$ANDROID_HOME` and `$ANDROID_NDK_HOME` are not set, source
`buildScript/init/env_ndk.sh` to set them:

```shell
source buildScript/init/env_ndk.sh
```

Then write the SDK path to `local.properties`:

```shell
echo "sdk.dir=${ANDROID_HOME}" > local.properties
```

Signing preparation (optional, it is recommended to sign after compilation): Replace `release.keystore` with your own
keystore.

```shell
echo "KEYSTORE_PASS=" >> local.properties
echo "ALIAS_NAME=" >> local.properties
echo "ALIAS_PASS=" >> local.properties
```

Download geo resource files:

```shell
make assets
```

Generate open source license metadata:

```shell
./gradlew :composeApp:exportLibraryDefinitions
```

Compile the release version:

```shell
make apk
```

The APK file will be located in `androidApp/build/outputs/apk`.

#### 🖥️ Desktop

Environment:

* jdk-21
* zig 0.15

Run the desktop application:

```shell
make desktop
```

Package a distributable for the current OS:

```shell
make desktop_package
```

This dispatches to the host-native packaging flow:

* Linux: `make desktop_package_linux`
* macOS: `make desktop_package_macos`
* Windows/MSYS: `make desktop_package_windows`

Build an **uber JAR** that runs on system Java (no bundled JRE/runtime image):

```shell
make desktop_uberjar
```

Output directory:

```shell
composeApp/build/compose/jars/
```

Run it with system Java (JDK/JRE 21+):

```shell
java -jar composeApp/build/compose/jars/fr.husi-<platform>-<arch>-<version>.jar
```

Build Linux native packages (`deb/rpm/pacman`) with Java 21+ dependency metadata (deb lists OpenJDK 21–26 alternatives; see `docs/DESKTOP_LINUX_CI.md`):

```shell
make desktop_package_linux
```

This command still builds the uber jar first, then packages it with native Linux tooling.
Required host tools: `zig`, `git`, `dpkg-deb`, `rpmbuild`, `bsdtar`, `zstd`.
If building `deb`, `gzip` is also required.

Package timestamps are derived from git tag `v<VERSION_NAME>` from `husi.properties`,
not from local build time.
Default output directory:

```shell
composeApp/build/compose/packages/linux/
```

You can select target formats:

```shell
make desktop_package_linux LINUX_PACKAGE_FORMATS=deb,pacman
```

Desktop data directory is `~/.config/husi/` (`$XDG_CONFIG_HOME/husi` if set).

Installed launcher supports user config files:

* `~/.config/husi/desktop-java-opts.conf` for JVM options
* `~/.config/husi/desktop-app-args.conf` for application startup arguments

Linux native packages include a native launcher built with Zig from `launcher/`.

Build the launcher standalone:

```shell
make launcher
```

The default packaging flow runs `make launcher` first, then `package.sh` consumes that binary.
Zig targets musl by default for static linking; no external C toolchain is needed.
Package install scripts call `setcap` on the launcher so capabilities can be raised to ambient set before starting the JVM.

You can preflight required tooling without producing packages:

```shell
./release/linux/package.sh --check-tools --formats deb,rpm,pacman
```

Build macOS `.dmg` packages with system Java runtime dependency:

```shell
make desktop_package_macos
```

This command builds the uber jar first, then packages it into `Husi.app` and a `.dmg` image.

The app bundle icon is a checked-in static asset generated from
`composeApp/src/commonMain/composeResources/drawable/ic_launcher_foreground.xml`,
so packaging no longer builds icons dynamically.

On macOS hosts it uses native tooling: `hdiutil`.
On Linux hosts it falls back to `genisoimage` or `mkisofs` and emits a compatibility `.dmg`
(an ISO9660/HFS hybrid image that macOS can mount). For Linux fallback, `DESKTOP_TARGET`
is required because the Gradle uber-jar task otherwise defaults to the Linux host target:

```shell
make desktop_package_macos DESKTOP_TARGET=darwin/arm64
```

Required host tools:

* Common: `zig`, `git`
* macOS host: `hdiutil`
* Linux fallback: `genisoimage` or `mkisofs`

Package timestamps are derived from git tag `v<VERSION_NAME>` from `husi.properties`,
not from local build time.
Default output directory:

```shell
composeApp/build/compose/packages/macos/
```

Installed app bundle uses the same native launcher from `launcher/` as Linux packaging.
User config files are created under:

* `~/Library/Application Support/husi/desktop-java-opts.conf` for JVM options
* `~/Library/Application Support/husi/desktop-app-args.conf` for application startup arguments

Build Windows portable zip and NSIS installer packages:

```shell
make desktop_package_windows DESKTOP_TARGET=windows/amd64
```

This command builds the uber jar first, then packages it with the same Zig launcher used by Linux and macOS.
Required host tools:

* `zig`
* `git`
* `python3`
* `makensis` (NSIS)

Default output directory:

```shell
composeApp/build/compose/packages/windows/
```

Outputs:

* `<PACKAGE_NAME>-<VERSION_NAME>-windows-<arch>.zip`
* `<PACKAGE_NAME>-<VERSION_NAME>-windows-<arch>-installer.exe`

The installer is a per-user NSIS installer that installs into `%LOCALAPPDATA%\Programs\Husi`,
creates a Start Menu shortcut, and registers the configured URL schemes for the current user.
The Windows launcher embeds an application manifest and requests administrator elevation via UAC at launch time.

**Runtime:** install **Temurin / OpenJDK 21+** (or another Java 21+ distribution) and ensure `java -version` reports 21 or newer. Portable zip, installer, and uber JAR do **not** bundle a JRE. If the process exits right after UAC with no window, an outdated Java is a common cause ([husi#30](https://codeberg.org/xchacha20-poly1305/husi/issues/30)).

**Windows JVM path:** the launcher checks `%APPDATA%\husi\desktop-java-home.conf` (first non-comment line: `JAVA_HOME` or full path to `java.exe`/`javaw.exe`) **before** `JAVA_HOME` / `PATH`, scans common install folders (including **`Program Files (x86)`** and `ProgramFiles(x86)` when set) for JDK 21+, and can open Temurin download or a file picker if nothing suitable is found. **`JAVA_HOME` pointing to Java 8/11/17/19 is ignored** (version check fails) so a newer JDK elsewhere can still be picked up. The launcher **never** writes to system `JAVA_HOME`; only your optional config file is updated when you use the file picker.

#### 🌈 Plugins

```shell
make plugin PLUGIN=<Plugin name>
```

Plugin name list:

* `hysteria2`
* `juicity`
* `naive` ( Deprecated. Build official repository directly, please. )
* `mieru`
* `shadowquic`

## 🏃‍♂️ Run

### Desktop

Requirement: >= Java Runtime **21**

_No bundled JRE for end users_

```shell
$ fr.husi --help
Usage: fr.husi [<options>] [<deep-link>]...

Options:
  -d, --dir=<path>       Data directory
  -l, --log-level=<int>  Log level override (0-6)
  -m, --many             Ignore exist instance
  -b, --background       Start without opening the main window
  -h, --help             Show this message and exit

Arguments:
  <deep-link>  Deep links
```

#### Arguments

URLs to import.

## ☠️ End users

[Wiki](https://codeberg.org/xchacha20-poly1305/husi/wiki)

## 📖 License

[GPL-3.0 or later](./LICENSE)

## 🤝 Acknowledgements

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)
- [XTLS/AnXray](https://github.com/XTLS/AnXray)
- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
- [SagerNet/sing-box-for-android](https://github.com/SagerNet/sing-box-for-android)
- [AntiNeko/CatBoxForAndroid](https://github.com/AntiNeko/CatBoxForAndroid)
- [MetaCubeX/ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid)
- [dyhkwong/Exclave](https://github.com/dyhkwong/Exclave)
- [chen08209/FlClash](https://github.com/chen08209/FlClash)
- [RikkaApps/RikkaX](https://github.com/RikkaApps/RikkaX)

Developing

- [![](https://resources.jetbrains.com/storage/products/company/brand/logos/jetbrains.svg)](https://www.jetbrains.com)

  JetBrains' powerful IDE.
