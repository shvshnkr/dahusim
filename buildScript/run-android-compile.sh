#!/usr/bin/env bash
# Android compile для ruleset_always_local (см. AI/cursorworklog.md 2026-08-31).
# Запуск: bash buildScript/long-run.sh start android-compile -- bash buildScript/run-android-compile.sh
# Долгий — самологируемый (docs/SCRIPTING_POLICY.md): лог + фазы + trap.
set -uo pipefail

LOG_DIR="${LOG_DIR:-artifacts}"
LOG="$LOG_DIR/run-android-compile.log"
mkdir -p "$LOG_DIR"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
trap 'log "ПРЕРВАНО на фазе: compileAndroidMain (см. $LOG)"; exit 130' INT TERM

log "шаг 1/1: :composeApp:compileAndroidMain"
# ANDROID_HOME обязателен (AGENTS.md: sdk.dir из local.properties не подхватывается);
# ABOUT_LIBRARIES_OFFLINE=true — иначе exportLibraryDefinitions висит на SPDX-фетче.
ANDROID_HOME=C:/Android/sdk ABOUT_LIBRARIES_OFFLINE=true cmd //c "chcp 65001 >nul & gradlew.bat :composeApp:compileAndroidMain --console=plain" 2>&1 | tee -a "$LOG"
RC=${PIPESTATUS[0]}
log "exit=$RC"
exit $RC
