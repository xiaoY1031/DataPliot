[CmdletBinding()]
# 文件使用 UTF-8 BOM 保存，确保 Windows PowerShell 5.1 正确解析中文。
param(
    [string]$BaseUri = "http://localhost:8082",
    [string]$CasesPath,
    [string]$OutputPath,
    [string]$DatabaseHost = $env:DATAPILOT_DB_HOST,
    [int]$DatabasePort = $(if ($env:DATAPILOT_DB_PORT) { [int]$env:DATAPILOT_DB_PORT } else { 0 }),
    [string]$DatabaseName = $env:DATAPILOT_DB_NAME,
    [string]$DatabaseUsername = $env:DATAPILOT_DB_USERNAME,
    [string]$DatabasePassword = $env:DATAPILOT_DB_PASSWORD,
    [string]$MySqlLoginPath,
    [string]$MySqlPath,
    [string]$ModelName = $env:LLM_MODEL,
    [string]$Set
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($CasesPath)) { $CasesPath = Join-Path $PSScriptRoot "..\..\evaluation\cases.json" }

. (Join-Path $PSScriptRoot "DataPilotEvaluation.Assertions.ps1")
. (Join-Path $PSScriptRoot "DataPilotEvaluation.DatabaseFingerprint.ps1")
. (Join-Path $PSScriptRoot "DataPilotEvaluation.Git.ps1")

function Get-FileSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-ConfiguredModelName {
    param([Parameter(Mandatory = $true)][string]$ApplicationConfigPath, [AllowNull()][string]$ExplicitModelName)
    if (-not [string]::IsNullOrWhiteSpace($ExplicitModelName)) { return $ExplicitModelName }
    if (Test-Path -LiteralPath $ApplicationConfigPath) {
        $text = Get-Content -LiteralPath $ApplicationConfigPath -Raw -Encoding UTF8
        $match = [regex]::Match($text, '(?m)^\s*model:\s*\$\{LLM_MODEL:([^}]+)\}\s*$')
        if ($match.Success) { return $match.Groups[1].Value.Trim() }
    }
    return $null
}

function Send-DataPilotEvaluationRequest {
    param([Parameter(Mandatory = $true)][string]$Message)
    $body = @{ message = $Message } | ConvertTo-Json -Compress
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$BaseUri/api/chat" -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
        $json = [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
        $payload = $json | ConvertFrom-Json
        return [PSCustomObject]@{
            Success=$true; StatusCode=[int]$response.StatusCode; TaskId=[string]$response.Headers["X-Task-Id"];
            TurnId=[string]$response.Headers["X-Turn-Id"]; ElapsedMs=$stopwatch.ElapsedMilliseconds;
            Answer=[string]$payload.answer; Error=$null
        }
    }
    catch {
        $statusCode = $null
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) { $statusCode = [int]$_.Exception.Response.StatusCode }
        return [PSCustomObject]@{
            Success=$false; StatusCode=$statusCode; TaskId=$null; TurnId=$null;
            ElapsedMs=$stopwatch.ElapsedMilliseconds; Answer=$null; Error=$_.Exception.Message
        }
    }
    finally { $stopwatch.Stop() }
}

function Write-EvaluationResult {
    param([Parameter(Mandatory = $true)]$RunResult, [Parameter(Mandatory = $true)][string]$Path)
    if (Test-Path -LiteralPath $Path) { throw "为避免覆盖历史评测结果，输出文件已存在：$Path" }
    $parent = Split-Path -Parent $Path
    if ($parent -and -not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $json = $RunResult | ConvertTo-Json -Depth 30
    [System.IO.File]::WriteAllText([System.IO.Path]::GetFullPath($Path), $json, (New-Object System.Text.UTF8Encoding($false)))
}

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$casesPathResolved = [System.IO.Path]::GetFullPath($CasesPath)
if (-not (Test-Path -LiteralPath $casesPathResolved)) { throw "未找到评测用例文件：$casesPathResolved" }
$definition = Get-Content -LiteralPath $casesPathResolved -Raw -Encoding UTF8 | ConvertFrom-Json
$runId = [guid]::NewGuid().ToString()
$startedAt = [DateTimeOffset]::Now
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $fileName = 'evaluation-v1-{0}-{1}.json' -f $startedAt.ToString('yyyyMMdd-HHmmss'), $runId.Substring(0, 8)
    $OutputPath = Join-Path $repositoryRoot (Join-Path 'evaluation-results' $fileName)
}
$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)
if (Test-Path -LiteralPath $OutputPath) { throw "为避免覆盖历史评测结果，输出文件已存在：$OutputPath" }

if ([string]::IsNullOrWhiteSpace($DatabaseHost)) { $DatabaseHost = [string]$definition.database.host }
if ($DatabasePort -le 0) { $DatabasePort = [int]$definition.database.port }
if ([string]::IsNullOrWhiteSpace($DatabaseName)) { $DatabaseName = [string]$definition.database.name }

$applicationConfigPath = Join-Path $repositoryRoot 'src\main\resources\application.yml'
$gitCommit = Get-GitCommitInfo -RepositoryRoot $repositoryRoot
$metadata = [ordered]@{
    evaluationRunId=$runId; startedAt=$startedAt.ToString('o'); completedAt=$null;
    gitCommitHash=$gitCommit.hash; gitCommitStatus=$gitCommit.status; gitCommitError=$gitCommit.errorReason; gitCommitExitCode=$gitCommit.exitCode;
    systemPromptSha256=Get-FileSha256 -Path (Join-Path $repositoryRoot 'src\main\resources\prompts\system.md');
    dataAnalysisSkillSha256=Get-FileSha256 -Path (Join-Path $repositoryRoot 'skills\data-analysis\SKILL.md');
    modelName=Get-ConfiguredModelName -ApplicationConfigPath $applicationConfigPath -ExplicitModelName $ModelName;
    baseUri=$BaseUri; set=([string]$Set); databaseIdentifier=('mysql://{0}:{1}/{2}' -f $DatabaseHost, $DatabasePort, $DatabaseName)
}
$runResult = [ordered]@{
    schemaVersion='1.0'; evaluationRunId=$runId; metadata=[PSCustomObject]$metadata;
    databaseFingerprint=$null; comparabilityStatus='PENDING'; runStatus='PENDING'; summary=$null; cases=@()
}

try {
    Write-Host "========== 数据库指纹校验 ==========" -ForegroundColor Cyan
    $fingerprint = Get-DataPilotDatabaseFingerprint -DatabaseConfig $definition.database -HostName $DatabaseHost -Port $DatabasePort -DatabaseName $DatabaseName -Username $DatabaseUsername -Password $DatabasePassword -LoginPath $MySqlLoginPath -MySqlPath $MySqlPath
    $runResult.databaseFingerprint = $fingerprint
    if (-not $fingerprint.baselineReady -or -not $fingerprint.allMatch) {
        $runResult.comparabilityStatus = 'NOT_COMPARABLE'
        $runResult.runStatus = 'ABORTED_DATABASE_FINGERPRINT_MISMATCH'
        throw '数据库指纹与 Gold 基线不一致，已在调用 LLM 前中止评测。'
    }
    $runResult.comparabilityStatus = 'COMPARABLE'
    Write-Host "指纹一致，允许开始评测。" -ForegroundColor Green

    $caseResults = @()
    $selectedCases = @($definition.cases)
    if (-not [string]::IsNullOrWhiteSpace($Set)) {
        $selectedCases = @($selectedCases | Where-Object { [string]$_.set -eq $Set })
        if ($selectedCases.Count -eq 0) { throw ("未找到集合 '{0}' 的评测用例（可用值：dev / heldout）。" -f $Set) }
    }
    foreach ($case in $selectedCases) {
        Write-Host ""
        Write-Host ("========== {0} ==========" -f $case.name) -ForegroundColor Cyan
        Write-Host $case.prompt
        $response = Send-DataPilotEvaluationRequest -Message ([string]$case.prompt)
        $assertions = if ($response.Success) { @(Invoke-DataPilotAssertions -Answer $response.Answer -Assertions @($case.assertions)) } else { @() }
        $caseStatus = Get-DataPilotCaseStatus -HttpSuccess $response.Success -AssertionResults $assertions
        $caseResults += [PSCustomObject]@{
            caseId=[string]$case.caseId; name=[string]$case.name; prompt=[string]$case.prompt; gold=$case.gold;
            finalAnswer=$response.Answer;
            http=[PSCustomObject]@{success=$response.Success;statusCode=$response.StatusCode;elapsedMs=$response.ElapsedMs;taskId=$response.TaskId;turnId=$response.TurnId;error=$response.Error};
            assertions=$assertions; status=$caseStatus
        }
        $color = if ($caseStatus -eq 'PASSED') { 'Green' } elseif ($caseStatus -eq 'NEEDS_MANUAL_REVIEW') { 'Yellow' } else { 'Red' }
        Write-Host ("{0}  http={1} elapsedMs={2}" -f $caseStatus, $response.StatusCode, $response.ElapsedMs) -ForegroundColor $color
        foreach ($assertionResult in $assertions) { Write-Host ("  [{0}] {1}" -f $assertionResult.status, $assertionResult.description) }
    }
    $runResult.cases = $caseResults
    $passed = @($caseResults | Where-Object { $_.status -eq 'PASSED' }).Count
    $needsManualReview = @($caseResults | Where-Object { $_.status -eq 'NEEDS_MANUAL_REVIEW' }).Count
    $failed = @($caseResults | Where-Object { $_.status -in @('FAILED','HTTP_FAILED') }).Count
    $automaticAssertions = @($caseResults.assertions | Where-Object { $_.status -in @('PASSED','FAILED') })
    $passedAssertions = @($automaticAssertions | Where-Object { $_.status -eq 'PASSED' }).Count
    $runResult.summary = [PSCustomObject]@{
        totalCases=$caseResults.Count; passedCases=$passed; needsManualReview=$needsManualReview; failedCases=$failed;
        automaticAssertions=$automaticAssertions.Count; passedAssertions=$passedAssertions;
        assertionPassRate=if($automaticAssertions.Count -gt 0){[math]::Round($passedAssertions/$automaticAssertions.Count,4)}else{$null}
    }
    $runResult.runStatus = if ($failed -eq 0) { 'COMPLETED' } else { 'COMPLETED_WITH_FAILURES' }
}
catch {
    if ($runResult.runStatus -eq 'PENDING') { $runResult.runStatus = 'FAILED_SETUP' }
    $runResult.summary = [PSCustomObject]@{setupError=$_.Exception.Message}
    Write-Host $_.Exception.Message -ForegroundColor Red
}
finally {
    $runResult.metadata.completedAt = [DateTimeOffset]::Now.ToString('o')
    Write-EvaluationResult -RunResult ([PSCustomObject]$runResult) -Path $OutputPath
    Write-Host ""
    Write-Host "评测结果已写入：$OutputPath" -ForegroundColor DarkGray
}

[PSCustomObject]$runResult
