#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

METADATA="$ROOT/husi.properties"
PKG_DIR="${PKG_DIR:-$ROOT/composeApp/build/compose/packages/linux}"
JAR_DIR="${JAR_DIR:-$ROOT/composeApp/build/compose/jars}"

PACKAGE_NAME="$(awk -F= '$1=="PACKAGE_NAME"{print $2; exit}' "$METADATA")"
VERSION_NAME="$(awk -F= '$1=="VERSION_NAME"{print $2; exit}' "$METADATA")"

test -n "$PACKAGE_NAME" && test -n "$VERSION_NAME"

fail=0
check_f() {
	local f="$1"
	local msg="$2"
	if [[ ! -f "$f" ]]; then
		echo "smoke: missing $msg: $f" >&2
		fail=1
	fi
}

uber="$(find "$JAR_DIR" -maxdepth 1 -type f -name "${PACKAGE_NAME}-linux-x64-*.jar" 2>/dev/null | head -n 1 || true)"
if [[ -z "$uber" ]]; then
	uber="$(find "$JAR_DIR" -maxdepth 1 -type f -name "${PACKAGE_NAME}-linux-x64-${VERSION_NAME}.jar" 2>/dev/null | head -n 1 || true)"
fi
if [[ -z "$uber" ]]; then
	echo "smoke: no uber jar under $JAR_DIR for ${PACKAGE_NAME}-linux-x64-*" >&2
	fail=1
else
	echo "smoke: uber jar ok: $uber"
fi

check_f "$PKG_DIR/${PACKAGE_NAME}_${VERSION_NAME}_amd64.deb" "deb"
mapfile -t rpms < <(find "$PKG_DIR" -maxdepth 1 -type f -name '*.rpm' -print || true)
if [[ ${#rpms[@]} -lt 1 ]]; then
	echo "smoke: missing rpm under $PKG_DIR" >&2
	fail=1
else
	echo "smoke: rpm ok: ${rpms[0]}"
fi

mapfile -t pkgs < <(find "$PKG_DIR" -maxdepth 1 -type f -name '*.pkg.tar.zst' -print || true)
if [[ ${#pkgs[@]} -lt 1 ]]; then
	echo "smoke: missing pacman pkg under $PKG_DIR" >&2
	fail=1
else
	echo "smoke: pacman ok: ${pkgs[0]}"
fi

if command -v dpkg-deb >/dev/null 2>&1; then
	deb="$PKG_DIR/${PACKAGE_NAME}_${VERSION_NAME}_amd64.deb"
	if [[ -f "$deb" ]]; then
		if ! dpkg-deb -I "$deb" >/dev/null 2>&1; then
			echo "smoke: dpkg-deb -I failed for $deb" >&2
			fail=1
		else
			depends="$(dpkg-deb -f "$deb" Depends 2>/dev/null || true)"
			if [[ -z "$depends" ]]; then
				echo "smoke: deb Depends field missing" >&2
				fail=1
			elif [[ "$depends" != *openjdk-21-jre* ]] || [[ "$depends" != *openjdk-26-jre* ]]; then
				echo "smoke: deb Depends missing OpenJDK 21–26 alternatives: $depends" >&2
				fail=1
			else
				echo "smoke: dpkg-deb metadata ok (Java 21–26 Depends)"
			fi
		fi
	fi
fi

if [[ "$fail" -ne 0 ]]; then
	exit 1
fi
echo "smoke: all checks passed"
