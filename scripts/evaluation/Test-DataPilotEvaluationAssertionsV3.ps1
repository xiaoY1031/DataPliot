[CmdletBinding()]
param()

# 针对 6 项解析器修复的新增单元测试（V3）：
#   1. 列名空白归一化（"用户ID" vs "用户 ID"）
#   2. 列名歧义检测（不静默 IndexOf 命中第一列）
#   3. 多表按所需列定位目标表
#   4. 全角/半角符号归一化（"）" vs ")"），不修改原始展示文本
#   5. labeledEntitySet 标签与实体分处相邻行的跨行提取（限制窗口）
#   6. labelValue 标签的空白/词语变体匹配（有界间隔子序列）

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "DataPilotEvaluation.Assertions.ps1")

function Assert-Synthetic {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)]$Assertion,
        [Parameter(Mandatory = $true)][string]$Answer,
        [Parameter(Mandatory = $true)][string]$ExpectedStatus,
        [string]$ReasonContains = ''
    )
    $result = @(Invoke-DataPilotAssertions -Answer $Answer -Assertions @($Assertion)) | Select-Object -First 1
    $passed = $result.status -eq $ExpectedStatus
    if ($passed -and $ReasonContains -ne '') {
        if ([string]$result.failureReason -notlike "*$ReasonContains*") { $passed = $false }
    }
    [PSCustomObject]@{
        name = $Name
        status = $result.status
        expected = $ExpectedStatus
        reasonContains = $ReasonContains
        passed = $passed
        failureReason = $result.failureReason
    }
}

# ============ Fix #1 列名空白归一化 ============
$whitespaceAssertion = [PSCustomObject]@{
    assertionId = 'synthetic-col-whitespace'
    type = 'tabularRows'
    description = '列名空白归一化。'
    keyColumns = @('用户 ID')
    columns = @(
        [PSCustomObject]@{ name = '用户 ID'; valueType = 'integer' }
        [PSCustomObject]@{ name = '竞赛排名'; valueType = 'integer' }
    )
    expectedRows = @([PSCustomObject]@{ '用户 ID' = 10; '竞赛排名' = 1 })
    mode = 'exact'
    expectedRowCount = 1
}

$whitespaceAnswer = @"
| 用户ID | 竞赛排名 |
| --- | --- |
| 10 | 1 |
"@

$fullwidthSpaceAnswer = @"
| 用户　ID | 竞赛排名 |
| --- | --- |
| 10 | 1 |
"@

# ============ Fix #2 列名歧义 ============
$shopAmbiguousAssertion = [PSCustomObject]@{
    assertionId = 'synthetic-shop-ambiguous'
    type = 'tabularRows'
    description = '店铺列歧义。'
    keyColumns = @('店铺')
    columns = @(
        [PSCustomObject]@{ name = '店铺'; valueType = 'string' }
        [PSCustomObject]@{ name = '排名'; valueType = 'integer' }
    )
    expectedRows = @([PSCustomObject]@{ '店铺' = '海底捞'; '排名' = 1 })
    mode = 'exact'
    expectedRowCount = 1
}

$shopAmbiguousAnswer = @"
| 店铺ID | 店铺名称 | 排名 |
| --- | --- | --- |
| 5 | 海底捞 | 1 |
"@

$shopExactAssertion = [PSCustomObject]@{
    assertionId = 'synthetic-shop-exact'
    type = 'tabularRows'
    description = '店铺列精确优先于子串。'
    keyColumns = @('店铺')
    columns = @(
        [PSCustomObject]@{ name = '店铺'; valueType = 'string' }
        [PSCustomObject]@{ name = '排名'; valueType = 'integer' }
    )
    expectedRows = @([PSCustomObject]@{ '店铺' = '海底捞'; '排名' = 1 })
    mode = 'exact'
    expectedRowCount = 1
}

$shopExactAnswer = @"
| 店铺 | 店铺名称 | 排名 |
| --- | --- | --- |
| 海底捞 | 海底捞火锅 | 1 |
"@

$priceSubstringAssertion = [PSCustomObject]@{
    assertionId = 'synthetic-price-substring'
    type = 'tabularRows'
    description = '单列子串匹配不误报歧义。'
    keyColumns = @('均价')
    columns = @(
        [PSCustomObject]@{ name = '均价'; valueType = 'integer' }
        [PSCustomObject]@{ name = '销量'; valueType = 'integer' }
    )
    expectedRows = @([PSCustomObject]@{ '均价' = 61; '销量' = 12035 })
    mode = 'exact'
    expectedRowCount = 1
}

$priceSubstringAnswer = @"
| 平均均价 | 销量 |
| --- | --- |
| 61 | 12035 |
"@

# ============ Fix #3 多表定位 ============
$multitableAssertion = [PSCustomObject]@{
    assertionId = 'synthetic-multitable'
    type = 'tabularRows'
    description = '多表按所需列定位。'
    keyColumns = @('用户 ID')
    columns = @(
        [PSCustomObject]@{ name = '用户 ID'; valueType = 'integer' }
        [PSCustomObject]@{ name = '昵称'; valueType = 'string' }
    )
    expectedRows = @([PSCustomObject]@{ '用户 ID' = 2; '昵称' = '可可' })
    mode = 'exact'
    expectedRowCount = 1
}

$multitableAnswer = @"
| 指标 | 数值 |
| --- | --- |
| 关注关系总数 | 101 |

| 用户 ID | 昵称 |
| --- | --- |
| 2 | 可可 |
"@

$nocolumnAssertion = [PSCustomObject]@{
    assertionId = 'synthetic-nocolumn'
    type = 'tabularRows'
    description = '多表无表同时包含所需列。'
    keyColumns = @('存储粉丝数')
    columns = @(
        [PSCustomObject]@{ name = '用户 ID'; valueType = 'integer' }
        [PSCustomObject]@{ name = '存储粉丝数'; valueType = 'integer' }
    )
    expectedRows = @([PSCustomObject]@{ '用户 ID' = 2; '存储粉丝数' = 3 })
    mode = 'exact'
    expectedRowCount = 1
}

# ============ Fix #4 全角/半角归一化 ============
$fullwidthParenAssertion = [PSCustomObject]@{
    assertionId = 'synthetic-shop-fullwidth-paren'
    type = 'tabularRows'
    description = '全角括号归一化。'
    keyColumns = @('店铺')
    columns = @(
        [PSCustomObject]@{ name = '店铺'; valueType = 'string' }
        [PSCustomObject]@{ name = '库存'; valueType = 'integer' }
    )
    expectedRows = @([PSCustomObject]@{ '店铺' = '海底捞火锅(水晶城购物中心店）'; '库存' = 67 })
    mode = 'exact'
    expectedRowCount = 1
}

$fullwidthParenAnswer = @"
| 店铺 | 库存 |
| --- | --- |
| 海底捞火锅(水晶城购物中心店) | 67 |
"@

$fullwidthLetterAssertion = [PSCustomObject]@{
    assertionId = 'synthetic-ktv-fullwidth-letter'
    type = 'tabularRows'
    description = '全角字母归一化。'
    keyColumns = @('分类')
    columns = @([PSCustomObject]@{ name = '分类'; valueType = 'string' })
    expectedRows = @([PSCustomObject]@{ '分类' = 'KTV' })
    mode = 'exact'
    expectedRowCount = 1
}

$fullwidthLetterAnswer = @"
| 分类 |
| --- |
| ＫＴＶ |
"@

# ============ Fix #5 labeledEntitySet 跨行提取 ============
$peakCrossAssertion = [PSCustomObject]@{
    assertionId = 'synthetic-peak-cross-line'
    type = 'labeledEntitySet'
    description = '标签与实体分处相邻行。'
    labelTerms = @('最大值的日期', '达到最大')
    entityPattern = '\d{4}-\d{2}-\d{2}'
    expectedEntities = @('2026-11-01', '2026-11-02', '2026-11-05', '2026-11-06')
    mode = 'exact'
}

$peakCrossAnswer = @"
签到人数达到最大值的全部日期
- 最大人数为 23 人，出现在 4 天：2026-11-01、2026-11-02、2026-11-05、2026-11-06
"@

$peakCrossBoundedAnswer = @"
签到人数达到最大值的全部日期
- 最大人数为 23 人，出现在 4 天：2026-11-01、2026-11-02、2026-11-05、2026-11-06
- 最少为 22 人，出现在 2026-11-03、2026-11-04、2026-11-07
"@

$peakCrossMissingAnswer = @"
签到人数达到最大值的全部日期
- 最大人数为 23 人，出现在 3 天：2026-11-01、2026-11-02、2026-11-05
"@

# ============ Fix #6 labelValue 词语变体 ============
$labelVariantAssertion = [PSCustomObject]@{
    assertionId = 'synthetic-label-variant'
    type = 'labelValue'
    description = '去重用户数词语变体。'
    labels = @('去重用户数')
    expected = 30
    valueType = 'number'
    absoluteTolerance = 0
    maxContextChars = 80
}

$labelVariantAnswer = '- 该时间段去重签到用户数：30 人'

$labelWhitespaceAssertion = [PSCustomObject]@{
    assertionId = 'synthetic-label-whitespace'
    type = 'labelValue'
    description = '标签空白变体。'
    labels = @('用户 ID')
    expected = 30
    valueType = 'number'
    absoluteTolerance = 0
    maxContextChars = 80
}

$labelWhitespaceAnswer = '用户ID：30 人'

$labelGapAssertion = [PSCustomObject]@{
    assertionId = 'synthetic-label-gap'
    type = 'labelValue'
    description = '过大的词语间隔不应模糊匹配。'
    labels = @('峰值')
    expected = 30
    valueType = 'number'
    absoluteTolerance = 0
    maxContextChars = 80
}

$labelGapAnswer = '峰甲乙丙值：30 人'

$tests = @(
    # Fix #1
    Assert-Synthetic -Name 'column-whitespace-normalize-passes' -Assertion $whitespaceAssertion -Answer $whitespaceAnswer -ExpectedStatus 'PASSED'
    Assert-Synthetic -Name 'column-fullwidth-space-normalize-passes' -Assertion $whitespaceAssertion -Answer $fullwidthSpaceAnswer -ExpectedStatus 'PASSED'
    # Fix #2
    Assert-Synthetic -Name 'column-ambiguous-shop-fail' -Assertion $shopAmbiguousAssertion -Answer $shopAmbiguousAnswer -ExpectedStatus 'FAILED' -ReasonContains '歧义'
    Assert-Synthetic -Name 'column-exact-beats-substring-passes' -Assertion $shopExactAssertion -Answer $shopExactAnswer -ExpectedStatus 'PASSED'
    Assert-Synthetic -Name 'column-single-substring-passes' -Assertion $priceSubstringAssertion -Answer $priceSubstringAnswer -ExpectedStatus 'PASSED'
    # Fix #3
    Assert-Synthetic -Name 'multitable-select-by-columns-passes' -Assertion $multitableAssertion -Answer $multitableAnswer -ExpectedStatus 'PASSED'
    Assert-Synthetic -Name 'multitable-no-table-has-all-fail' -Assertion $nocolumnAssertion -Answer $multitableAnswer -ExpectedStatus 'FAILED' -ReasonContains '未找到同时包含所需列'
    # Fix #4
    Assert-Synthetic -Name 'fullwidth-paren-shop-match-passes' -Assertion $fullwidthParenAssertion -Answer $fullwidthParenAnswer -ExpectedStatus 'PASSED'
    Assert-Synthetic -Name 'fullwidth-letter-match-passes' -Assertion $fullwidthLetterAssertion -Answer $fullwidthLetterAnswer -ExpectedStatus 'PASSED'
    # Fix #5
    Assert-Synthetic -Name 'labeled-entity-adjacent-line-passes' -Assertion $peakCrossAssertion -Answer $peakCrossAnswer -ExpectedStatus 'PASSED'
    Assert-Synthetic -Name 'labeled-entity-bounded-no-cross-para-passes' -Assertion $peakCrossAssertion -Answer $peakCrossBoundedAnswer -ExpectedStatus 'PASSED'
    Assert-Synthetic -Name 'labeled-entity-missing-fail' -Assertion $peakCrossAssertion -Answer $peakCrossMissingAnswer -ExpectedStatus 'FAILED'
    # Fix #6
    Assert-Synthetic -Name 'labelvalue-word-variant-passes' -Assertion $labelVariantAssertion -Answer $labelVariantAnswer -ExpectedStatus 'PASSED'
    Assert-Synthetic -Name 'labelvalue-whitespace-variant-passes' -Assertion $labelWhitespaceAssertion -Answer $labelWhitespaceAnswer -ExpectedStatus 'PASSED'
    Assert-Synthetic -Name 'labelvalue-loose-gap-no-match-fail' -Assertion $labelGapAssertion -Answer $labelGapAnswer -ExpectedStatus 'FAILED'
)

$tests | Format-Table -AutoSize
$failedTests = @($tests | Where-Object { -not $_.passed })
if ($failedTests.Count -gt 0) {
    $failedTests | ConvertTo-Json -Depth 8 | Write-Host
    throw "新增断言引擎 V3 Mock 测试失败：$($failedTests.Count) 项。"
}

Write-Host "全部 $($tests.Count) 项 V3 断言引擎 Mock 测试通过。" -ForegroundColor Green
