$ErrorActionPreference = "Stop"

$SingBoxVersion = "v1.14.0-alpha.12"
$XhttpSourceVersion = "v1.13.12-extended-2.4.0.0.20260607061226-9c80cf371c19"
$XhttpSourceModule = "github.com/shtorm-7/sing-box-extended"

$LibcoreRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$DepsDir = Join-Path $LibcoreRoot "deps\sing-box"
$Overlay = Join-Path $PSScriptRoot "overlay"

Push-Location $LibcoreRoot
try {
    $env:GOTOOLCHAIN = "auto"
    $modCache = (go env GOMODCACHE).Trim()
    $upstream = Join-Path $modCache "github.com\sagernet\sing-box@${SingBoxVersion}"
    if (Test-Path $upstream) {
        Remove-Item -Recurse -Force $upstream
    }
    go mod download "github.com/sagernet/sing-box@${SingBoxVersion}" | Out-Null
    go mod download "${XhttpSourceModule}@${XhttpSourceVersion}" | Out-Null
    $xhttpSource = go list -m -f "{{.Dir}}" "${XhttpSourceModule}@${XhttpSourceVersion}"
    if (-not (Test-Path $upstream) -or -not $xhttpSource) {
        throw "Failed to resolve sing-box module directories"
    }

    if (Test-Path $DepsDir) {
        Remove-Item -Recurse -Force $DepsDir
    }
    New-Item -ItemType Directory -Path $DepsDir | Out-Null
    Copy-Item -Recurse -Force (Join-Path $upstream "*") $DepsDir
    Get-ChildItem -Path $DepsDir -Recurse | ForEach-Object { $_.IsReadOnly = $false }

    Remove-Item -Recurse -Force (Join-Path $DepsDir "common\xray") -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force (Join-Path $DepsDir "transport\v2rayxhttp") -ErrorAction SilentlyContinue
    Copy-Item -Recurse -Force (Join-Path $xhttpSource "common\xray") (Join-Path $DepsDir "common\xray")
    Copy-Item -Recurse -Force (Join-Path $xhttpSource "transport\v2rayxhttp") (Join-Path $DepsDir "transport\v2rayxhttp")

    Copy-Item -Force (Join-Path $Overlay "constant\v2ray.go") (Join-Path $DepsDir "constant\v2ray.go")
    Copy-Item -Force (Join-Path $Overlay "transport\v2ray\transport.go") (Join-Path $DepsDir "transport\v2ray\transport.go")
    Copy-Item -Force (Join-Path $Overlay "option\v2ray_transport.go") (Join-Path $DepsDir "option\v2ray_transport.go")
    Copy-Item -Force (Join-Path $Overlay "transport\v2rayhttp\xhttp_compat.go") (Join-Path $DepsDir "transport\v2rayhttp\xhttp_compat.go")

    Write-Host "Prepared patched sing-box at $DepsDir"
} finally {
    Pop-Location
}
