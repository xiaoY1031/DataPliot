[CmdletBinding()]
param([string]$RepositoryRoot)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) { $RepositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')) }
. (Join-Path $PSScriptRoot 'DataPilotEvaluation.Git.ps1')

$directOutput = @(& git -c ("safe.directory=" + $RepositoryRoot.Replace('\','/')) -C $RepositoryRoot rev-parse HEAD 2>&1)
$directExitCode = $LASTEXITCODE
if ($directExitCode -ne 0) { throw "Direct git rev-parse HEAD failed: $($directOutput -join ' ')" }
$directHash = ([string]($directOutput | Select-Object -First 1)).Trim().ToLowerInvariant()
$global:LASTEXITCODE = 99
$captured = Get-GitCommitInfo -RepositoryRoot $RepositoryRoot
if ($captured.status -ne 'AVAILABLE' -or $captured.hash -ne $directHash) { throw "Captured Git hash does not match direct git output." }

$tempRoot = Join-Path (Split-Path -Parent $RepositoryRoot) ('datapilot-non-git-' + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
    $missing = Get-GitCommitInfo -RepositoryRoot $tempRoot
    if ($missing.status -ne 'UNAVAILABLE' -or $null -ne $missing.hash -or [string]::IsNullOrWhiteSpace([string]$missing.errorReason)) {
        throw 'A non-Git directory must return UNAVAILABLE with no hash and a clear error reason.'
    }
}
finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}

[PSCustomObject]@{ directHash=$directHash; capturedHash=$captured.hash; status=$captured.status; nonGitStatus=$missing.status; nonGitError=$missing.errorReason }
Write-Host 'Git metadata tests passed.' -ForegroundColor Green
