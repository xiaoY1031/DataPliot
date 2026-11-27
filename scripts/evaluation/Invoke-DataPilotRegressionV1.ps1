[CmdletBinding()]
param()

# V1 回归：用已落盘的 finalAnswer（v1-dev/v1-heldout 结果 JSON）重新跑断言引擎，
# 对比解析器修复前后的 case 状态与断言状态变化。不重跑真实 LLM。

$ErrorActionPreference = "Stop"
$repo = "F:\DataPilot\coding-harness"
. (Join-Path $repo "scripts\evaluation\DataPilotEvaluation.Assertions.ps1")

$defMap = @{}
$defs = Get-Content -LiteralPath (Join-Path $repo "evaluation\cases.json") -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($c in @($defs.cases)) { $defMap[[string]$c.caseId] = $c }

$report = @()
foreach ($resultFile in @('v1-dev-20261126.json', 'v1-heldout-20261126.json')) {
    $rj = Get-Content -LiteralPath (Join-Path $repo ("evaluation-results\" + $resultFile)) -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($rc in @($rj.cases)) {
        $cid = [string]$rc.caseId
        $def = $defMap[$cid]
        if ($null -eq $def) { Write-Warning "无 case 定义：$cid"; continue }
        $answer = [string]$rc.finalAnswer
        $newResults = @(Invoke-DataPilotAssertions -Answer $answer -Assertions @($def.assertions))
        $oldStatus = [string]$rc.status
        $newStatus = Get-DataPilotCaseStatus -HttpSuccess $true -AssertionResults $newResults

        $oldAssertMap = @{}
        foreach ($a in @($rc.assertions)) { $oldAssertMap[[string]$a.assertionId] = $a }

        $assertionCompare = @()
        foreach ($nr in $newResults) {
            $aid = [string]$nr.assertionId
            $oldA = if ($oldAssertMap.ContainsKey($aid)) { $oldAssertMap[$aid] } else { $null }
            $old = if ($null -ne $oldA) { [string]$oldA.status } else { '(missing-old)' }
            $oldReason = if ($null -ne $oldA) { [string]$oldA.failureReason } else { '' }
            $new = [string]$nr.status
            $newReason = [string]$nr.failureReason
            $assertionCompare += [PSCustomObject]@{
                assertionId = $aid
                oldStatus = $old
                newStatus = $new
                statusChanged = ($old -ne $new)
                oldReason = $oldReason
                newReason = $newReason
                reasonChanged = ($oldReason -ne $newReason)
            }
        }
        $assertionsChanged = @($assertionCompare | Where-Object { $_.statusChanged -or $_.reasonChanged })

        $report += [PSCustomObject]@{
            set = if ($resultFile -like 'v1-dev*') { 'dev' } else { 'heldout' }
            caseId = $cid
            oldStatus = $oldStatus
            newStatus = $newStatus
            changed = ($oldStatus -ne $newStatus)
            assertionsChanged = $assertionsChanged
            assertionCompare = $assertionCompare
        }
    }
}

$outPath = Join-Path $repo "evaluation-results\v1-regression-20261126.json"
$report | ConvertTo-Json -Depth 6 | Out-File -LiteralPath $outPath -Encoding utf8
Write-Host ("regression report -> " + $outPath)

Write-Host ""
Write-Host "===== 汇总 ====="
foreach ($r in $report) {
    $marker = if ($r.changed) { "CHANGED" } else { "same   " }
    $n = @($r.assertionsChanged).Count
    Write-Host ("[{0}] {1,-26} {2} -> {3}  断言变化={4}" -f $marker, $r.caseId, $r.oldStatus, $r.newStatus, $n)
    foreach ($ac in $r.assertionsChanged) {
        $kind = if ($ac.statusChanged) { "状态" } else { "原因" }
        Write-Host ("        [{0}] {1}: {2} -> {3}" -f $kind, $ac.assertionId, $ac.oldStatus, $ac.newStatus)
        Write-Host ("            旧原因: {0}" -f $ac.oldReason)
        Write-Host ("            新原因: {0}" -f $ac.newReason)
    }
}
