#!/usr/bin/env bash
# Verify pinned Temurin 21 GitHub download URLs respond before CI / packaging uses them.
set -euo pipefail
ROOT="${GITHUB_WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
# shellcheck source=../temurin21-pinned.urls
source "$ROOT/buildScript/temurin21-pinned.urls"

check_url() {
	local label="$1"
	local url="$2"
	local code
	code="$(curl -fsSLI -o /dev/null -w '%{http_code}' "$url")"
	if [[ "$code" != "200" && "$code" != "302" ]]; then
		echo "verify_temurin21_urls: $label HTTP $code — $url" >&2
		return 1
	fi
	echo "verify_temurin21_urls: $label OK ($code)"
}

check_url "msi_x64" "$TEMURIN21_WIN_X64_MSI"
check_url "msi_arm64" "$TEMURIN21_WIN_ARM64_MSI"
check_url "zip" "$TEMURIN21_WIN_X64_ZIP"
check_url "releases_page" "$TEMURIN21_RELEASES_PAGE"
echo "verify_temurin21_urls: all OK ($TEMURIN21_TAG)"
