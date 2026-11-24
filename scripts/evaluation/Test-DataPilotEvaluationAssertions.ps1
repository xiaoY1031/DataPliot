[CmdletBinding()]
param([string]$CasesPath)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($CasesPath)) { $CasesPath = Join-Path $PSScriptRoot "..\..\evaluation\cases.json" }
. (Join-Path $PSScriptRoot "DataPilotEvaluation.Assertions.ps1")

$definition = Get-Content -LiteralPath $CasesPath -Raw -Encoding UTF8 | ConvertFrom-Json

function Get-CaseDefinition {
    param([Parameter(Mandatory = $true)][string]$CaseId)
    return $definition.cases | Where-Object { $_.caseId -eq $CaseId } | Select-Object -First 1
}

function Assert-MockCaseStatus {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$CaseId,
        [Parameter(Mandatory = $true)][string]$Answer,
        [Parameter(Mandatory = $true)][string]$ExpectedStatus,
        [string[]]$ExpectedFailedAssertionIds = @()
    )
    $case = Get-CaseDefinition -CaseId $CaseId
    if ($null -eq $case) { throw "未找到用例：$CaseId" }
    $results = @(Invoke-DataPilotAssertions -Answer $Answer -Assertions @($case.assertions))
    $status = Get-DataPilotCaseStatus -HttpSuccess $true -AssertionResults $results
    $failedIds = @($results | Where-Object { $_.status -eq 'FAILED' } | ForEach-Object { $_.assertionId })
    $passed = $status -eq $ExpectedStatus
    foreach ($expectedId in $ExpectedFailedAssertionIds) {
        if ($failedIds -notcontains $expectedId) { $passed = $false }
    }
    [PSCustomObject]@{name=$Name;status=$status;expectedStatus=$ExpectedStatus;failedAssertionIds=$failedIds;passed=$passed}
}

$validCategoryAnswer = @"
全库总店铺数为 30 家。
| 排名 | 分类 | 店铺数 | 占比 |
| 1 | 美食 | 9 家 | 30.00% |
| 2 | KTV | 5 家 | 16.67% |
| 3 | 丽人·美发 | 2 家 | 6.67% |
| 3 | 健身运动 | 2 家 | 6.67% |
| 3 | 按摩·足疗 | 2 家 | 6.67% |
| 3 | 美容SPA | 2 家 | 6.67% |
| 3 | 亲子游乐 | 2 家 | 6.67% |
| 3 | 酒吧 | 2 家 | 6.67% |
| 3 | 轰趴馆 | 2 家 | 6.67% |
| 3 | 美睫·美甲 | 2 家 | 6.67% |
Top 2 为美食和 KTV，合计 14 家，占 46.67%。
"@

$validEstimatedAnswer = @"
以下金额按均价 × 销量估算，并非真实营收：
1. Mamala(杭州远洋乐堤港店)：3,920,510 元
2. INLOVE KTV(水晶城店)：2,698,275 元
3. 开乐迪KTV（运河上街店）：1,801,697 元
4. 幸福里老北京涮锅（丝联店）：1,239,030 元
5. 讴K拉量贩KTV(北城天地店)：1,101,826 元
全部店铺总估算金额为 1,977.7107 万元；Top 5 合计 1,076.1338 万元，占 54.41%。
"@

$sequentialCategoryRanksAnswer = $validCategoryAnswer
$rank = 3
foreach ($category in @('丽人·美发','健身运动','按摩·足疗','美容SPA','亲子游乐','酒吧','轰趴馆','美睫·美甲')) {
    $sequentialCategoryRanksAnswer = $sequentialCategoryRanksAnswer -replace ("\| 3 \| " + [regex]::Escape($category) + " \|"), ("| " + $rank + " | " + $category + " |")
    $rank++
}

$missingCategoryRankAnswer = $validCategoryAnswer -replace '\| 3 \| 酒吧 \| 2 家 \| 6\.67% \|', '|  | 酒吧 | 2 家 | 6.67% |'
$mismatchedCategoryRankAnswer = ($validCategoryAnswer -replace '\| 1 \| 美食 \| 9 家 \| 30\.00% \|', '| 2 | 美食 | 9 家 | 30.00% |') -replace '\| 2 \| KTV \| 5 家 \| 16\.67% \|', '| 1 | KTV | 5 家 | 16.67% |'
$groupedRankAnswer = @"
全库总店铺数为 30 家。
美食：9 家，排名第 1，占 30.00%。
KTV：5 家，排名第 2，占 16.67%。
丽人·美发：2 家，占 6.67%。
健身运动：2 家，占 6.67%。
按摩·足疗：2 家，占 6.67%。
美容SPA：2 家，占 6.67%。
亲子游乐：2 家，占 6.67%。
酒吧：2 家，占 6.67%。
轰趴馆：2 家，占 6.67%。
美睫·美甲：2 家，占 6.67%。
上述其余八类全部并列第 3。
Top 2 为美食和 KTV，合计 14 家，占 46.67%。
"@

$tests = @(
    Assert-MockCaseStatus -Name 'valid-shop-count' -CaseId 'shop-count' -Answer '当前数据库共有 30 家店铺，统计对象为 tb_shop 中的门店记录。' -ExpectedStatus 'PASSED'
    Assert-MockCaseStatus -Name 'unrelated-thirty-does-not-pass' -CaseId 'shop-count' -Answer '一共有 29 家店铺，另外查询耗时 30 毫秒。' -ExpectedStatus 'FAILED' -ExpectedFailedAssertionIds @('shop-count-value')
    Assert-MockCaseStatus -Name 'valid-competition-ties' -CaseId 'shop-category-distribution' -Answer $validCategoryAnswer -ExpectedStatus 'PASSED'
    Assert-MockCaseStatus -Name 'sequential-ranks-fail' -CaseId 'shop-category-distribution' -Answer $sequentialCategoryRanksAnswer -ExpectedStatus 'FAILED' -ExpectedFailedAssertionIds @('category-count-ranks')
    Assert-MockCaseStatus -Name 'missing-tied-rank-does-not-pass' -CaseId 'shop-category-distribution' -Answer $missingCategoryRankAnswer -ExpectedStatus 'FAILED' -ExpectedFailedAssertionIds @('category-count-ranks')
    Assert-MockCaseStatus -Name 'category-name-rank-mismatch-fails' -CaseId 'shop-category-distribution' -Answer $mismatchedCategoryRankAnswer -ExpectedStatus 'FAILED' -ExpectedFailedAssertionIds @('category-count-ranks')
    Assert-MockCaseStatus -Name 'grouped-rank-needs-manual-review' -CaseId 'shop-category-distribution' -Answer $groupedRankAnswer -ExpectedStatus 'NEEDS_MANUAL_REVIEW'
    Assert-MockCaseStatus -Name 'wrong-top2-share' -CaseId 'shop-category-distribution' -Answer ($validCategoryAnswer -replace '46.67%', '45.00%') -ExpectedStatus 'FAILED' -ExpectedFailedAssertionIds @('top2-share')
    Assert-MockCaseStatus -Name 'valid-estimated-amount-with-wan-format' -CaseId 'estimated-sales-top5' -Answer $validEstimatedAnswer -ExpectedStatus 'PASSED'
    Assert-MockCaseStatus -Name 'wrong-entity-amount-does-not-pass' -CaseId 'estimated-sales-top5' -Answer ($validEstimatedAnswer -replace '3,920,510', '3,000,000') -ExpectedStatus 'FAILED' -ExpectedFailedAssertionIds @('top5-order-and-amounts')
    Assert-MockCaseStatus -Name 'false-real-revenue-fails' -CaseId 'estimated-sales-top5' -Answer ($validEstimatedAnswer + [Environment]::NewLine + '以上就是实际营收。') -ExpectedStatus 'FAILED' -ExpectedFailedAssertionIds @('false-real-revenue-absent')
)

$tests | Format-Table -AutoSize
$failedTests = @($tests | Where-Object { -not $_.passed })
if ($failedTests.Count -gt 0) {
    $failedTests | ConvertTo-Json -Depth 8 | Write-Host
    throw "Evaluation 断言引擎 Mock 测试失败：$($failedTests.Count) 项。"
}

Write-Host "全部 $($tests.Count) 项断言引擎 Mock 测试通过。" -ForegroundColor Green
