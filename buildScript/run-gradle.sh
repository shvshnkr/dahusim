#!/usr/bin/env bash
# run-gradle.sh — единая обёртка gradle-запусков (замена одноразовых run-*-771/772/775.sh).
# env вшит: ANDROID_HOME (sdk.dir не подхватывается) + ABOUT_LIBRARIES_OFFLINE=true
# (иначе exportLibraryDefinitions висит на SPDX-фетче — см. AGENTS.md).
# Всегда через long-run.sh: лог artifacts/<name>.log, статус/log/stop — не ждать молча.
#
# Usage (из корня репо):
#   bash buildScript/run-gradle.sh desk-tests-777 -- :composeApp:desktopTest --tests "fr.husi.X"
#   bash buildScript/run-gradle.sh android-compile-777 -- :composeApp:compileAndroidMain
# Мониторинг: bash buildScript/long-run.sh status|log|stop <name>
set -u
[ $# -ge 3 ] || { echo "usage: run-gradle.sh <name> -- <gradle args...>"; exit 2; }
name="$1"; shift
if [ "${1:-}" = "--" ]; then shift; fi
[ $# -ge 1 ] || { echo "run-gradle.sh: пустая gradle-команда"; exit 2; }
for a in "$@"; do
  case "$a" in
    *[\"\'\ ]*) echo "run-gradle.sh: аргумент '$a' содержит пробел/кавычку — не поддерживается (передавай простые токены)"; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/artifacts"
inner_rel="artifacts/.run-gradle-$name.sh"
{
  echo 'set -u'
  echo 'cd "$(dirname "$0")/.."'
  echo 'export ANDROID_HOME="C:/Android/sdk"'
  echo 'export ABOUT_LIBRARIES_OFFLINE=true'
  printf 'echo "[run-gradle] gradlew.bat %s --console=plain"\n' "$*"
  printf 'cmd //c "chcp 65001 >nul & gradlew.bat %s --console=plain"\n' "$*"
  echo 'rc=$?'
  echo 'echo "[run-gradle] exit=$rc"'
  echo 'exit "$rc"'
} > "$ROOT/$inner_rel"

exec bash "$ROOT/buildScript/long-run.sh" start "$name" -- bash "$inner_rel"
