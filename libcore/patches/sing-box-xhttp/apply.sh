#!/usr/bin/env bash
set -euo pipefail

SING_BOX_VERSION="v1.14.0-alpha.12"
XHTTP_SOURCE_VERSION="v1.13.12-extended-2.4.0.0.20260607061226-9c80cf371c19"
XHTTP_SOURCE_MODULE="github.com/shtorm-7/sing-box-extended"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OVERLAY="$(cd "$(dirname "$0")/overlay" && pwd)"
DEPS_DIR="${ROOT}/deps/sing-box"

cd "$ROOT"

export GOTOOLCHAIN=auto

mod_cache="$(go env GOMODCACHE)"
upstream="${mod_cache}/github.com/sagernet/sing-box@${SING_BOX_VERSION}"

go mod download "github.com/sagernet/sing-box@${SING_BOX_VERSION}"
go mod download "${XHTTP_SOURCE_MODULE}@${XHTTP_SOURCE_VERSION}"
xhttp_source="$(go list -m -f '{{.Dir}}' "${XHTTP_SOURCE_MODULE}@${XHTTP_SOURCE_VERSION}")"

if [ ! -d "${upstream}" ] || [ -z "${xhttp_source}" ]; then
    echo "Failed to resolve sing-box module directories" >&2
    exit 1
fi

rm -rf "${DEPS_DIR}"
mkdir -p "${DEPS_DIR}"
cp -R "${upstream}/." "${DEPS_DIR}/"
chmod -R u+w "${DEPS_DIR}"

rm -rf "${DEPS_DIR}/common/xray" "${DEPS_DIR}/transport/v2rayxhttp"
cp -R "${xhttp_source}/common/xray" "${DEPS_DIR}/common/xray"
cp -R "${xhttp_source}/transport/v2rayxhttp" "${DEPS_DIR}/transport/v2rayxhttp"

cp "${OVERLAY}/constant/v2ray.go" "${DEPS_DIR}/constant/v2ray.go"
cp "${OVERLAY}/transport/v2ray/transport.go" "${DEPS_DIR}/transport/v2ray/transport.go"
cp "${OVERLAY}/option/v2ray_transport.go" "${DEPS_DIR}/option/v2ray_transport.go"
cp "${OVERLAY}/transport/v2rayhttp/xhttp_compat.go" "${DEPS_DIR}/transport/v2rayhttp/xhttp_compat.go"

echo "Prepared patched sing-box at ${DEPS_DIR}"
