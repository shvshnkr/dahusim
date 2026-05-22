# Dahusim

**Dahusim** — форк Android-клиента [husi](https://codeberg.org/xchacha20-poly1305/husi) с упором на работу "из коробки" в цензурируемых сетях: простой режим, автовыбор серверов и готовые пресеты маршрутизации для РФ.

Upstream (`husi`) живет и развивается на Codeberg, а этот репозиторий — ветка Dahusim с собственными релизами, дефолтами и UX-решениями.

## Что это и для кого

- **Что:** клиент для обхода сетевых ограничений на базе sing-box.
- **Зачем:** быстро подключаться без ручной настройки десятков параметров.
- **Для кого:** в первую очередь для пользователей внутри РФ, которым нужен рабочий старт "поставил и поехали".
- **Где сложное:** в "полном интерфейсе" остаются все продвинутые настройки.

## Быстрый старт (пользователь)

### Android APK (pre-release, динамический список)

- [Последние Android pre-release](https://github.com/shvshnkr/dahusim/releases?q=android-play+prerelease%3Atrue)
- Обычно нужен файл `*_arm64_v8a.apk` (для большинства современных телефонов).
- APK берите из блока `Assets`, не из исходников.

### Desktop Linux (pre-release, динамический список)

- [Последние Linux desktop pre-release](https://github.com/shvshnkr/dahusim/releases?q=linux-desktop-linux-amd64+prerelease%3Atrue)
- В `Assets` обычно есть `deb`/`rpm`/`pkg.tar.zst` и `jar`.
- Нужна Java 21+ (в сборки JRE не вшит).

### Desktop Windows (pre-release, динамический список)

- [Последние Windows desktop pre-release](https://github.com/shvshnkr/dahusim/releases?q=windows-desktop-windows-amd64+prerelease%3Atrue)
- Сейчас поддерживается только `windows/amd64` (x64).
- Нужна Java 21+ (в сборки JRE не вшит).

## Ключевые возможности

- **Простой режим:** минимальный UX для быстрого подключения.
- **Автовыбор сервера:** ранжирование и fallback при нестабильных узлах.
- **Адаптация при смене сети:** переподбор и переподключение при Wi-Fi/LTE handoff.
- **RU-оптимизация:** пресеты и маршрутизация под сценарии РФ.
- **Desktop headless:** daemon-режим, `--ctl`, pseudo GUI, systemd-команды для Linux.

Подробнее по оптимизациям: [docs/RU-OPTIMIZATION.md](docs/RU-OPTIMIZATION.md)

## Важно понимать

- Приложение предоставляется **as is**, без гарантий работоспособности в любой сети и без гарантий безопасности конкретных публичных серверов.
- Автовыбор может использовать открытые/community-узлы. Доверие к таким узлам — на вашей стороне.
- Использование приложения и сетевых сервисов должно соответствовать законам вашей юрисдикции.
- Это open-source инструмент и исследовательский проект, а не "коммерческий VPN-сервис под SLA".

## Обновления в приложении

- Канал обновлений описан в [docs/APP_UPDATE.md](docs/APP_UPDATE.md).
- Для desktop установка апдейта остается пользовательской (скачать/запустить установщик).

## Где что искать

- Пользовательская документация: `docs/`
- Сборка и CI: [BUILD.md](./BUILD.md)
- Вклад и стиль кода: [CONTRIBUTING.md](./CONTRIBUTING.md)
- Синхронизация с апстримом: [docs/UPSTREAM_SYNC.md](docs/UPSTREAM_SYNC.md)
- Промпт для AI code review (diff/PR и аудит по волнам): [docs/CODE_REVIEW_PROMPT.md](docs/CODE_REVIEW_PROMPT.md)

## Технические детали (для разработчиков)

### Минимум для локальной разработки

- JDK 21+
- Android SDK/NDK (для Android-сборок)
- `make`/Gradle

Базовые команды:

```shell
# Android APK
make apk

# Desktop запуск
make desktop

# Desktop package под текущую ОС
make desktop_package
```

Полные инструкции по платформам, libcore, packaging, CI и зависимостям:
[BUILD.md](./BUILD.md)

### Desktop CLI (кратко)

```shell
fr.husi --help
fr.husi --daemon
fr.husi --ctl status
fr.husi --systemd install
fr.husi --pseudo-gui
```

Подробнее: секции в [BUILD.md](./BUILD.md) и документация в `docs/`.

## Alert

Google объявил о требованиях developer verification для Android-разработки с 2026 года. Контекст и позиция open-source сообщества:
[Keep Android Open](https://keepandroidopen.org/)

## License

[GPL-3.0 or later](./LICENSE)

## Acknowledgements

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)
- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
- [SagerNet/sing-box-for-android](https://github.com/SagerNet/sing-box-for-android)

---

## English (short)

Dahusim is a Russia-oriented fork of husi with a simpler default UX, auto-selection/fallback logic, and network handoff adaptation.  
Latest pre-releases:

- Android: <https://github.com/shvshnkr/dahusim/releases?q=android-play+prerelease%3Atrue>
- Linux desktop: <https://github.com/shvshnkr/dahusim/releases?q=linux-desktop-linux-amd64+prerelease%3Atrue>
- Windows desktop: <https://github.com/shvshnkr/dahusim/releases?q=windows-desktop-windows-amd64+prerelease%3Atrue>
