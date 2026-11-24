Set-StrictMode -Version 2.0

function Get-GitCommitInfo {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)

    $resolvedRoot = [System.IO.Path]::GetFullPath($RepositoryRoot)
    try {
        $gitCommand = Get-Command git -ErrorAction Stop
        $output = @(& $gitCommand.Source -c ("safe.directory=" + $resolvedRoot.Replace('\','/')) -C $resolvedRoot rev-parse HEAD 2>&1)
        $gitExitCode = $LASTEXITCODE
        if ($gitExitCode -ne 0) {
            $errorText = (@($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine).Trim()
            if ([string]::IsNullOrWhiteSpace($errorText)) { $errorText = "git rev-parse HEAD failed with exit code $gitExitCode." }
            return [PSCustomObject]@{ status='UNAVAILABLE'; hash=$null; errorReason=$errorText; exitCode=$gitExitCode }
        }
        $hash = [string]($output | Select-Object -First 1)
        $hash = $hash.Trim().ToLowerInvariant()
        if ($hash -notmatch '^[0-9a-f]{40,64}$') {
            return [PSCustomObject]@{ status='UNAVAILABLE'; hash=$null; errorReason='git rev-parse HEAD returned an invalid commit hash.'; exitCode=$gitExitCode }
        }
        return [PSCustomObject]@{ status='AVAILABLE'; hash=$hash; errorReason=$null; exitCode=$gitExitCode }
    }
    catch {
        return [PSCustomObject]@{ status='UNAVAILABLE'; hash=$null; errorReason=$_.Exception.Message; exitCode=$null }
    }
}
