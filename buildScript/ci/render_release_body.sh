#!/usr/bin/env bash
# Render GitHub pre-release body (RU boilerplate + «Что нового»).
# Usage: render_release_body.sh <android|linux|windows> <version_name> <version_code> <short_sha> <run_id> <run_number> [repo_url]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=changelog_lib.sh
source "$SCRIPT_DIR/changelog_lib.sh"

platform="${1:?platform}"
VER="${2:?version_name}"
VER_CODE="${3:?version_code}"
SHORT_SHA="${4:?short_sha}"
RUN_ID="${5:?run_id}"
RUN_NUMBER="${6:?run_number}"
REPO_URL="${7:-}"

HEAD_SHA="${HEAD_SHA:-$(git -C "$REPO_ROOT" rev-parse HEAD)}"
export HEAD_SHA RUN_ID
VERSION_NAME="$VER"
VERSION_CODE="$VER_CODE"
export VERSION_NAME VERSION_CODE

whats_new="$(mktemp)"
merge_release_notes >"$whats_new"

out="${RELEASE_BODY_FILE:-release_body.md}"

append_whats_new() {
  if [ -s "$whats_new" ]; then
    echo ""
    echo "## Что нового"
    echo ""
    cat "$whats_new"
  fi
}

case "$platform" in
  android)
    {
      echo "## Pre-release: dahusim $VER"
      echo
      echo "**versionName:** \`$VER\` · **versionCode:** \`$VER_CODE\` · **commit:** \`$SHORT_SHA\` · **CI:** #${RUN_NUMBER} · **run:** \`${RUN_ID}\`"
      echo
      echo "**Как скачать:** внизу страницы блок **Assets** — по одному APK на архитектуру (**arm64-v8a**, **armeabi-v7a**, **x86_64**, **x86**). Имена вида \`dahusim_<версия>_play_debug_<abi>.apk\`."
      echo
      echo "**Этот pre-release — только Android APK.** Сборки **Linux / Windows desktop** (zip, deb, …) публикуются **другими** workflows; для desktop на ПК нужен **Java 21+**, см. README."
      echo
      echo "В этой ветке в описаниях **«КВН»** используется **вместо** слова «VPN» (шутливая подмена, не гарантия «услуги»). Возможен **автовыбор** публичных/открытых серверов энтузиастов — риск и закон на вас, см. README."
      append_whats_new
    } >"$out"
    ;;
  linux)
    TAG="linux-desktop-linux-amd64-${RUN_ID}"
    {
      echo "## Linux desktop · **linux/amd64**"
      echo
      echo "**Платформа:** \`linux/amd64\` (сборка DESKTOP_TARGET=linux/amd64)."
      echo
      echo "**Среда выполнения:** uber JAR и нативный лаунчер — **Java 21+** (CI: Temurin 21–26, workflow \`jdk-matrix-test.yml\`)."
      echo "В **deb** в \`Depends\` перечислены \`javaNN-runtime\` / \`openjdk-NN-jre\` для N=21…26 (достаточно одного); rpm/pacman — \`>= 21\`."
      echo
      echo "**versionName:** \`${VER}\` · **versionCode:** \`${VER_CODE}\` · **commit:** \`${SHORT_SHA}\` · **тег:** \`${TAG}\` · **CI:** #${RUN_NUMBER}"
      echo
      echo "**Где скачать:** репозиторий → вкладка **Releases** (не артефакты Actions) → этот пре-релиз → внизу **Assets**."
      if [ -n "$REPO_URL" ]; then
        echo
        echo "[Все релизы репозитория](${REPO_URL}/releases)"
      fi
      echo
      echo "Отдельный тестовый канал (не смешивается с Android APK pre-release workflow \`build.yml\`)."
      echo
      echo "**Форматы в Assets:** deb, rpm, pacman (.pkg.tar.zst), uber JAR."
      echo
      echo "**Примечание:** uber JAR крупный из‑за JNI и вложенных нативных библиотек; контекст у upstream: [husi#9](https://codeberg.org/xchacha20-poly1305/husi/issues/9)."
      append_whats_new
    } >"$out"
    ;;
  windows)
    TAG="windows-desktop-windows-amd64-${RUN_ID}"
    {
      echo "## Windows desktop · **windows/amd64**"
      echo
      echo "**Платформа:** \`windows/amd64\` (сборка DESKTOP_TARGET=windows/amd64)."
      echo
      echo "**versionName:** \`${VER}\` · **versionCode:** \`${VER_CODE}\` · **commit:** \`${SHORT_SHA}\` · **тег:** \`${TAG}\` · **CI:** #${RUN_NUMBER}"
      echo
      echo "**Где скачать:** репозиторий → вкладка **Releases** (не артефакты Actions) → этот пре-релиз → внизу **Assets**."
      if [ -n "$REPO_URL" ]; then
        echo
        echo "[Все релизы репозитория](${REPO_URL}/releases)"
      fi
      echo
      echo "Отдельный тестовый канал (не смешивается с Android APK pre-release workflow \`build.yml\`)."
      echo
      echo "**Форматы в Assets:** portable zip, NSIS installer (.exe), uber JAR."
      echo
      echo "**Требование:** на целевой машине **Java 21+** (JRE не входит в zip/installer/JAR). Симптом «UAC и тишина» при старой Java: [husi#30](https://codeberg.org/xchacha20-poly1305/husi/issues/30). Кракозябры в трее/уведомлениях: [husi#79](https://codeberg.org/xchacha20-poly1305/husi/issues/79), [husi#81](https://codeberg.org/xchacha20-poly1305/husi/issues/81)."
      append_whats_new
    } >"$out"
    ;;
  *)
    echo "Unknown platform: $platform" >&2
    exit 1
    ;;
esac

rm -f "$whats_new"
