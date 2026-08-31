#!/usr/bin/env bash
# Прогон desktop-тестов для ruleset_always_local (см. AI/cursorworklog.md 2026-08-31).
# Запуск: bash buildScript/long-run.sh start desk-tests -- bash buildScript/run-desktop-tests.sh
# Долгий — самологируемый (docs/SCRIPTING_POLICY.md): лог + фазы + trap.
set -uo pipefail

LOG_DIR="${LOG_DIR:-artifacts}"
LOG="$LOG_DIR/run-desktop-tests.log"
mkdir -p "$LOG_DIR"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
trap 'log "ПРЕРВАНО на фазе: desktopTest (см. $LOG)"; exit 130' INT TERM

TESTS=(
  fr.husi.fmt.ConfigBuilderTest
  fr.husi.fmt.SingBoxOptionsUtilKtTest
  fr.husi.scenario.network.RuleSetBootstrapScenarioTest
  fr.husi.bg.RouteAssetAutoUpdateTest
  fr.husi.bg.RouteAssetAutoUpdateSyntheticTest
)

ARGS=()
for T in "${TESTS[@]}"; do ARGS+=(--tests "$T"); done

log "шаг 1/1: :composeApp:desktopTest (${#TESTS[@]} классов)"
# ABOUT_LIBRARIES_OFFLINE=true обязателен: без него exportLibraryDefinitions висит на SPDX-фетче
# (LicenseUtil → raw.githubusercontent.com, без таймаутов; DPI режет JVM-TLS) — известный ханг,
# рецепт из AI/cursorworklog 23.08 (флаг -P ломается обёрткой).
ABOUT_LIBRARIES_OFFLINE=true cmd //c "chcp 65001 >nul & gradlew.bat :composeApp:desktopTest --console=plain ${ARGS[*]}" 2>&1 | tee -a "$LOG"
RC=${PIPESTATUS[0]}
log "exit=$RC"
exit $RC
