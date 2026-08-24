#!/usr/bin/env bash
# Validation for simple-screen v2 states (mockup-v2-states.html):
#   1. desktopTest: SimpleHomeScreenStatusToneTest + SimpleScreenStateTonesJourneyTest
#   2. simpleModeUiTest (desktop Compose UI tests, fr.husi.ui.simple.*)
#   3. compileAndroidMain
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="C:/Android/sdk"
export ABOUT_LIBRARIES_OFFLINE="true"
mkdir -p artifacts

echo "=== [1/3] desktopTest: SimpleHomeScreenStatusToneTest + SimpleScreenStateTonesJourneyTest ==="
./gradlew.bat :composeApp:desktopTest \
  --tests "fr.husi.ui.simple.SimpleHomeScreenStatusToneTest" \
  --tests "fr.husi.scenario.journey.SimpleScreenStateTonesJourneyTest"

echo "=== [2/3] simpleModeUiTest ==="
./gradlew.bat :composeApp:simpleModeUiTest

echo "=== [3/3] compileAndroidMain ==="
./gradlew.bat :composeApp:compileAndroidMain

echo "ALL DONE"
