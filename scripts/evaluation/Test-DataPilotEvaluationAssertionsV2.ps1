[CmdletBinding()]
param()

# 新增断言类型（tabularRows、labeledEntitySet）的独立单元测试。
# 覆盖设计文档 7.4 的全部要求：
#   - 多个正确数字出现在同一行但列错位时必须失败。
#   - 重复行、漏行、额外行在 exactRows 模式下必须失败。
#   - 1,1,1,4,4,4 通过，1,2,3,4,5,6 失败。
#   - 日期集合漏掉一个并列峰值时失败。
#   - NULL 与 0 不得默认视为相等。
#   - 货币 298 元与 29800 分只能在断言明确配置单位换算时视为相等。
# 另覆盖 ordered 顺序校验与 string contains 键匹配。

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "DataPilotEvaluation.Assertions.ps1")

function Assert-SyntheticStatus {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)]$Assertion,
        [Parameter(Mandatory = $true)][string]$Answer,
        [Parameter(Mandatory = $true)][string]$ExpectedStatus
    )
    $result = @(Invoke-DataPilotAssertions -Answer $Answer -Assertions @($Assertion)) | Select-Object -First 1
    [PSCustomObject]@{
        name = $Name
        status = $result.status
        expected = $ExpectedStatus
        passed = $result.status -eq $ExpectedStatus
        failureReason = $result.failureReason
    }
}

# ============ 构造通用 tabularRows 断言（商圈 Top 3 风格） ============
function New-AreaRowsAssertion {
    param([bool]$Ordered = $true, [string]$Mode = 'exact', [int]$ExpectedRowCount = 3)
    [PSCustomObject]@{
        assertionId = 'synthetic-area-rows'
        type = 'tabularRows'
        description = '商圈 Top 3 行级验证。'
        keyColumns = @('商圈')
        columns = @(
            [PSCustomObject]@{ name = '商圈'; valueType = 'string' }
            [PSCustomObject]@{ name = '店铺数'; valueType = 'integer' }
            [PSCustomObject]@{ name = '平均均价'; valueType = 'decimal'; absoluteTolerance = 0.01 }
            [PSCustomObject]@{ name = '总销量'; valueType = 'integer' }
        )
        expectedRows = @(
            [PSCustomObject]@{ '商圈' = '运河上街'; '店铺数' = 7; '平均均价' = 95.86; '总销量' = 51191 }
            [PSCustomObject]@{ '商圈' = '北部新城'; '店铺数' = 4; '平均均价' = 112.25; '总销量' = 23733 }
            [PSCustomObject]@{ '商圈' = '大关'; '店铺数' = 4; '平均均价' = 142.50; '总销量' = 10296 }
        )
        mode = $Mode
        ordered = $Ordered
        expectedRowCount = $ExpectedRowCount
    }
}

$areaHeader = @"
| 商圈 | 店铺数 | 平均均价 | 总销量 |
| --- | --- | --- | --- |
"@ + "`n"

$validAreaAnswer = $areaHeader + @"
| 运河上街 | 7 | 95.86 | 51191 |
| 北部新城 | 4 | 112.25 | 23733 |
| 大关 | 4 | 142.50 | 10296 |
"@

$misalignedAreaAnswer = $areaHeader + @"
| 运河上街 | 7 | 51191 | 95.86 |
| 北部新城 | 4 | 23733 | 112.25 |
| 大关 | 4 | 10296 | 142.50 |
"@

$missingRowAnswer = $areaHeader + @"
| 运河上街 | 7 | 95.86 | 51191 |
| 北部新城 | 4 | 112.25 | 23733 |
"@

$extraRowAnswer = $areaHeader + @"
| 运河上街 | 7 | 95.86 | 51191 |
| 北部新城 | 4 | 112.25 | 23733 |
| 大关 | 4 | 142.50 | 10296 |
| 额外商圈 | 1 | 50.00 | 100 |
"@

$duplicateRowAnswer = $areaHeader + @"
| 运河上街 | 7 | 95.86 | 51191 |
| 运河上街 | 7 | 95.86 | 51191 |
| 北部新城 | 4 | 112.25 | 23733 |
"@

$wrongOrderAnswer = $areaHeader + @"
| 大关 | 4 | 142.50 | 10296 |
| 北部新城 | 4 | 112.25 | 23733 |
| 运河上街 | 7 | 95.86 | 51191 |
"@

# ============ 竞赛排名断言 ============
function New-CompetitionRankAssertion {
    [PSCustomObject]@{
        assertionId = 'synthetic-user-paid-rank'
        type = 'tabularRows'
        description = '竞赛排名验证：金额相同并列、下一名次跳号。'
        keyColumns = @('用户ID')
        columns = @(
            [PSCustomObject]@{ name = '用户ID'; valueType = 'integer' }
            [PSCustomObject]@{ name = '竞赛排名'; valueType = 'integer' }
            [PSCustomObject]@{ name = '累计支付金额'; valueType = 'currency' }
        )
        expectedRows = @(
            [PSCustomObject]@{ '用户ID' = 10; '累计支付金额' = 298.00 }
            [PSCustomObject]@{ '用户ID' = 22; '累计支付金额' = 298.00 }
            [PSCustomObject]@{ '用户ID' = 34; '累计支付金额' = 298.00 }
            [PSCustomObject]@{ '用户ID' = 14; '累计支付金额' = 258.00 }
            [PSCustomObject]@{ '用户ID' = 26; '累计支付金额' = 258.00 }
            [PSCustomObject]@{ '用户ID' = 38; '累计支付金额' = 258.00 }
        )
        mode = 'exact'
        ordered = $false
        rankMode = 'competition'
        rankColumn = '竞赛排名'
        metricColumn = '累计支付金额'
    }
}

$rankHeader = @"
| 用户ID | 竞赛排名 | 累计支付金额 |
| --- | --- | --- |
"@ + "`n"

$validRankAnswer = $rankHeader + @"
| 10 | 1 | 298.00 元 |
| 22 | 1 | 298.00 元 |
| 34 | 1 | 298.00 元 |
| 14 | 4 | 258.00 元 |
| 26 | 4 | 258.00 元 |
| 38 | 4 | 258.00 元 |
"@

$sequentialRankAnswer = $rankHeader + @"
| 10 | 1 | 298.00 元 |
| 22 | 2 | 298.00 元 |
| 34 | 3 | 298.00 元 |
| 14 | 4 | 258.00 元 |
| 26 | 5 | 258.00 元 |
| 38 | 6 | 258.00 元 |
"@

# ============ NULL 与 0 区分 ============
$nullVsZeroAssertion = [PSCustomObject]@{
    assertionId = 'synthetic-null-vs-zero'
    type = 'tabularRows'
    description = 'NULL 不得默认视为 0。'
    keyColumns = @('id')
    columns = @(
        [PSCustomObject]@{ name = 'id'; valueType = 'integer' }
        [PSCustomObject]@{ name = 'cnt'; valueType = 'integer' }
    )
    expectedRows = @(
        [PSCustomObject]@{ 'id' = 1; 'cnt' = 0 }
    )
    mode = 'exact'
    expectedRowCount = 1
}

$nullCellAnswer = @"
| id | cnt |
| --- | --- |
| 1 | NULL |
"@

$zeroCellAnswer = @"
| id | cnt |
| --- | --- |
| 1 | 0 |
"@

$nullValueTypeAssertion = [PSCustomObject]@{
    assertionId = 'synthetic-null-value-type'
    type = 'tabularRows'
    description = 'null 类型列期望 NULL 时，0 不得视为 NULL。'
    keyColumns = @('id')
    columns = @(
        [PSCustomObject]@{ name = 'id'; valueType = 'integer' }
        [PSCustomObject]@{ name = 'v'; valueType = 'null' }
    )
    expectedRows = @(
        [PSCustomObject]@{ 'id' = 1; 'v' = $null }
    )
    mode = 'exact'
    expectedRowCount = 1
}

$nullExpectedAnswer = @"
| id | v |
| --- | --- |
| 1 | NULL |
"@

$zeroExpectedAnswer = @"
| id | v |
| --- | --- |
| 1 | 0 |
"@

# ============ 货币单位换算 ============
function New-CurrencyAssertion {
    param([bool]$AllowUnitConversion = $false)
    [PSCustomObject]@{
        assertionId = 'synthetic-currency'
        type = 'tabularRows'
        description = '货币单位：默认不把分当元。'
        keyColumns = @('id')
        columns = @(
            [PSCustomObject]@{ name = 'id'; valueType = 'integer' }
            [PSCustomObject]@{ name = '金额'; valueType = 'currency'; currencyUnit = 'yuan'; allowUnitConversion = $AllowUnitConversion }
        )
        expectedRows = @(
            [PSCustomObject]@{ 'id' = 1; '金额' = 298.00 }
        )
        mode = 'exact'
        expectedRowCount = 1
    }
}

$currencyYuanAnswer = @"
| id | 金额 |
| --- | --- |
| 1 | 298.00 元 |
"@

$currencyCentAnswer = @"
| id | 金额 |
| --- | --- |
| 1 | 29800 分 |
"@

# ============ labeledEntitySet 峰值日期 ============
$peakDatesAssertion = [PSCustomObject]@{
    assertionId = 'synthetic-peak-dates'
    type = 'labeledEntitySet'
    description = '峰值日期集合必须精确相等。'
    labelTerms = @('峰值日期', '达到最大', '最多', '最大值的日期')
    entityPattern = '\d{4}-\d{2}-\d{2}'
    expectedEntities = @('2026-11-01', '2026-11-02', '2026-11-05', '2026-11-06')
    mode = 'exact'
}

$validPeakAnswer = @"
每日签到人数见下表；签到记录总数 158，去重用户数 30，最大签到人数 23。
达到最大值的日期：2026-11-01、2026-11-02、2026-11-05、2026-11-06。
"@

$missingPeakAnswer = @"
每日签到人数见下表；签到记录总数 158，去重用户数 30，最大签到人数 23。
达到最大值的日期：2026-11-01、2026-11-02、2026-11-05。
"@

$extraPeakAnswer = @"
每日签到人数见下表；签到记录总数 158，去重用户数 30，最大签到人数 23。
达到最大值的日期：2026-11-01、2026-11-02、2026-11-03、2026-11-05、2026-11-06。
"@

# ============ string contains 键匹配（状态标签） ============
$containsKeyAssertion = [PSCustomObject]@{
    assertionId = 'synthetic-contains-key'
    type = 'tabularRows'
    description = '状态标签键使用 contains 匹配。'
    keyColumns = @('状态')
    columns = @(
        [PSCustomObject]@{ name = '状态'; valueType = 'string'; match = 'contains' }
        [PSCustomObject]@{ name = '订单数'; valueType = 'integer' }
    )
    expectedRows = @(
        [PSCustomObject]@{ '状态' = '未支付'; '订单数' = 6 }
        [PSCustomObject]@{ '状态' = '已支付'; '订单数' = 5 }
    )
    mode = 'exact'
    expectedRowCount = 2
}

$containsKeyAnswer = @"
| 状态 | 订单数 |
| --- | --- |
| 1 未支付 | 6 |
| 2 已支付 | 5 |
"@

$tests = @(
    # 列错位
    Assert-SyntheticStatus -Name 'tabular-valid-area-passes' -Assertion (New-AreaRowsAssertion) -Answer $validAreaAnswer -ExpectedStatus 'PASSED'
    Assert-SyntheticStatus -Name 'tabular-misaligned-columns-fail' -Assertion (New-AreaRowsAssertion) -Answer $misalignedAreaAnswer -ExpectedStatus 'FAILED'
    # 漏行 / 额外行 / 重复行
    Assert-SyntheticStatus -Name 'tabular-missing-row-fail' -Assertion (New-AreaRowsAssertion) -Answer $missingRowAnswer -ExpectedStatus 'FAILED'
    Assert-SyntheticStatus -Name 'tabular-extra-row-fail' -Assertion (New-AreaRowsAssertion) -Answer $extraRowAnswer -ExpectedStatus 'FAILED'
    Assert-SyntheticStatus -Name 'tabular-duplicate-row-fail' -Assertion (New-AreaRowsAssertion) -Answer $duplicateRowAnswer -ExpectedStatus 'FAILED'
    # 顺序
    Assert-SyntheticStatus -Name 'tabular-wrong-order-fail' -Assertion (New-AreaRowsAssertion -Ordered $true) -Answer $wrongOrderAnswer -ExpectedStatus 'FAILED'
    Assert-SyntheticStatus -Name 'tabular-unordered-passes' -Assertion (New-AreaRowsAssertion -Ordered $false) -Answer $wrongOrderAnswer -ExpectedStatus 'PASSED'
    # 竞赛排名
    Assert-SyntheticStatus -Name 'competition-rank-gap-passes' -Assertion (New-CompetitionRankAssertion) -Answer $validRankAnswer -ExpectedStatus 'PASSED'
    Assert-SyntheticStatus -Name 'competition-sequential-rows-fail' -Assertion (New-CompetitionRankAssertion) -Answer $sequentialRankAnswer -ExpectedStatus 'FAILED'
    # NULL 与 0
    Assert-SyntheticStatus -Name 'null-vs-zero-integer-fail' -Assertion $nullVsZeroAssertion -Answer $nullCellAnswer -ExpectedStatus 'FAILED'
    Assert-SyntheticStatus -Name 'zero-vs-null-value-type-fail' -Assertion $nullValueTypeAssertion -Answer $zeroExpectedAnswer -ExpectedStatus 'FAILED'
    Assert-SyntheticStatus -Name 'null-expected-null-passes' -Assertion $nullValueTypeAssertion -Answer $nullExpectedAnswer -ExpectedStatus 'PASSED'
    Assert-SyntheticStatus -Name 'integer-zero-passes' -Assertion $nullVsZeroAssertion -Answer $zeroCellAnswer -ExpectedStatus 'PASSED'
    # 货币单位
    Assert-SyntheticStatus -Name 'currency-yuan-passes' -Assertion (New-CurrencyAssertion) -Answer $currencyYuanAnswer -ExpectedStatus 'PASSED'
    Assert-SyntheticStatus -Name 'currency-cent-no-conversion-fail' -Assertion (New-CurrencyAssertion) -Answer $currencyCentAnswer -ExpectedStatus 'FAILED'
    Assert-SyntheticStatus -Name 'currency-cent-with-conversion-passes' -Assertion (New-CurrencyAssertion -AllowUnitConversion $true) -Answer $currencyCentAnswer -ExpectedStatus 'PASSED'
    # 峰值日期集合
    Assert-SyntheticStatus -Name 'peak-dates-valid-passes' -Assertion $peakDatesAssertion -Answer $validPeakAnswer -ExpectedStatus 'PASSED'
    Assert-SyntheticStatus -Name 'peak-dates-missing-fail' -Assertion $peakDatesAssertion -Answer $missingPeakAnswer -ExpectedStatus 'FAILED'
    Assert-SyntheticStatus -Name 'peak-dates-extra-fail' -Assertion $peakDatesAssertion -Answer $extraPeakAnswer -ExpectedStatus 'FAILED'
    # contains 键匹配
    Assert-SyntheticStatus -Name 'contains-key-passes' -Assertion $containsKeyAssertion -Answer $containsKeyAnswer -ExpectedStatus 'PASSED'
)

$tests | Format-Table -AutoSize
$failedTests = @($tests | Where-Object { -not $_.passed })
if ($failedTests.Count -gt 0) {
    $failedTests | ConvertTo-Json -Depth 8 | Write-Host
    throw "新增断言引擎 Mock 测试失败：$($failedTests.Count) 项。"
}

Write-Host "全部 $($tests.Count) 项新增断言引擎 Mock 测试通过。" -ForegroundColor Green
