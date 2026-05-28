# PowerShell wrapper for buildScript/ci/gh-workflow.sh (Git Bash) or direct gh calls.
param(
    [Parameter(Position = 0)]
    [string]$Command = "help",
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rest
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BashScript = Join-Path $ScriptDir "gh-workflow.sh"

function Invoke-GhWorkflowSh {
    $bash = Get-Command bash -ErrorAction SilentlyContinue
    if (-not $bash) {
        throw "bash not found; install Git for Windows or use WSL to run gh-workflow.sh"
    }
    & $bash.Source $BashScript $Command @Rest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

if ($Command -in @("help", "-h", "--help", "")) {
    & { $bash = Get-Command bash -ErrorAction SilentlyContinue; if ($bash) { & $bash.Source $BashScript help } else { Write-Host "See buildScript/ci/gh-workflow.sh" } }
    exit 0
}

try {
    Invoke-GhWorkflowSh
}
catch {
    Write-Error $_
    exit 1
}
