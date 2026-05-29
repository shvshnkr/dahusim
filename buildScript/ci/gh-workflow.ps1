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

function Invoke-PrunePrereleases {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$PruneOpts)

  $keepRunId = $null
  $dryRun = $false
  $limit = 80
  for ($i = 0; $i -lt $PruneOpts.Count; $i++) {
    switch ($PruneOpts[$i]) {
      "--keep-run-id" { $keepRunId = $PruneOpts[++$i]; continue }
      "--dry-run" { $dryRun = $true; continue }
      "-dry-run" { $dryRun = $true; continue }
      "--limit" { $limit = [int]$PruneOpts[++$i]; continue }
      default { throw "Unknown prune-prereleases option: $($PruneOpts[$i])" }
    }
  }

  $repo = (gh repo view --json nameWithOwner -q .nameWithOwner)
  if (-not $keepRunId) {
    $runs = gh run list --repo $repo --workflow all-platforms-build.yml --branch main --limit 30 `
      --json databaseId,conclusion | ConvertFrom-Json
    $keepRunId = ($runs | Where-Object { $_.conclusion -eq "success" } | Select-Object -First 1).databaseId
    if (-not $keepRunId) { throw "No successful all-platforms run on main; use --keep-run-id" }
    Write-Host "Keeping pre-release trio for run_id=$keepRunId"
  } else {
    Write-Host "Keeping pre-release trio for run_id=$keepRunId (explicit)"
  }

  $keepTags = @(
    "android-$keepRunId",
    "linux-desktop-linux-amd64-$keepRunId",
    "windows-desktop-windows-amd64-$keepRunId",
    "rolling-$keepRunId"
  )

  $ciTagPattern = '^(android|rolling)-\d+$|^linux-desktop-linux-amd64-\d+$|^windows-desktop-windows-amd64-\d+$'
  $deleted = 0
  $tags = gh release list --repo $repo --limit $limit --json tagName -q '.[].tagName'
  foreach ($tag in $tags) {
    if ($tag -notmatch $ciTagPattern) { continue }
    if ($keepTags -contains $tag) {
      Write-Host "keep  $tag"
      continue
    }
    if ($dryRun) {
      Write-Host "would delete $tag"
      continue
    }
    gh release delete $tag --repo $repo --yes --cleanup-tag 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
      Write-Host "deleted $tag"
      $deleted++
    } else {
      Write-Host "skip  $tag (not found or not deletable)"
    }
  }

  if ($dryRun) {
    Write-Host "Dry-run complete (kept run_id=$keepRunId)."
  } else {
    Write-Host "Done: deleted=$deleted, kept tags for run_id=$keepRunId."
  }
}

if ($Command -in @("help", "-h", "--help", "")) {
    & { $bash = Get-Command bash -ErrorAction SilentlyContinue; if ($bash) { & $bash.Source $BashScript help } else { Write-Host "See buildScript/ci/gh-workflow.sh" } }
    exit 0
}

if ($Command -eq "prune-prereleases") {
  try {
    Invoke-PrunePrereleases @Rest
    exit 0
  } catch {
    Write-Error $_
    exit 1
  }
}

try {
    Invoke-GhWorkflowSh
}
catch {
    Write-Error $_
    exit 1
}
