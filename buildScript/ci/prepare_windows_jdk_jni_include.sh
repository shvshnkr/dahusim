#!/usr/bin/env bash
# Temurin Windows x64 JDK 21: only include/ + include/win32/ for cross-compiling
# libcore on Linux (anja needs win32/jni_md.h; host JAVA_HOME is Linux).
set -euo pipefail
ROOT="${GITHUB_WORKSPACE:-$PWD}"
# shellcheck source=../temurin21-pinned.urls
source "$ROOT/buildScript/temurin21-pinned.urls"
CACHE="${WIN_JDK_JNI_CACHE:-$ROOT/.cache/win-jdk21-jni}"
ZIP="$CACHE/jdk-win-x64.zip"
EXTRACT="$CACHE/extract"
mkdir -p "$CACHE"
if [ ! -f "$ZIP" ]; then
	curl -fsSL "$TEMURIN21_WIN_X64_ZIP" -o "$ZIP.part"
	mv "$ZIP.part" "$ZIP"
fi
rm -rf "$EXTRACT"
mkdir -p "$EXTRACT"
unzip -q "$ZIP" -d "$EXTRACT"
JNI_H="$(find "$EXTRACT" -type f -path '*/include/jni.h' | head -n1)"
if [ -z "$JNI_H" ]; then
	echo "prepare_windows_jdk_jni_include: jni.h not found under $EXTRACT" >&2
	exit 1
fi
INCLUDE_DIR="$(cd "$(dirname "$JNI_H")" && pwd)"
if [ ! -f "$INCLUDE_DIR/win32/jni_md.h" ]; then
	echo "prepare_windows_jdk_jni_include: missing $INCLUDE_DIR/win32/jni_md.h" >&2
	exit 1
fi
if [ -n "${GITHUB_ENV:-}" ]; then
	echo "JNI_INCLUDE=$INCLUDE_DIR" >>"$GITHUB_ENV"
	echo ">> JNI_INCLUDE=$INCLUDE_DIR (written to GITHUB_ENV)"
else
	echo "export JNI_INCLUDE=$INCLUDE_DIR"
fi
