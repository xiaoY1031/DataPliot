Set-StrictMode -Version 2.0

function ConvertTo-NormalizedEvaluationText {
    param([AllowNull()][string]$Text)
    if ($null -eq $Text) { return "" }
    return $Text.Replace([char]0x00A0, ' ').Replace([char]0x3000, ' ') -replace '\*\*|__', ''
}

function ConvertFrom-EvaluationNumberToken {
    param([Parameter(Mandatory = $true)][string]$Token)
    $trimmed = $Token.Trim()
    $isPercent = $trimmed.Contains('%') -or $trimmed.Contains('％')
    $multiplier = if ($trimmed.Contains('亿')) { 100000000.0 } elseif ($trimmed.Contains('万')) { 10000.0 } else { 1.0 }
    $numberText = $trimmed -replace '[,，\s]', ''
    $numberText = $numberText -replace '[%％万亿元人民币家个条笔]', ''
    $value = 0.0
    $style = [System.Globalization.NumberStyles]::Float -bor [System.Globalization.NumberStyles]::AllowLeadingSign
    if (-not [double]::TryParse($numberText, $style, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$value)) { return $null }
    $unit = $null
    $unitMatch = [regex]::Match($trimmed, '(元|人民币|家|个|条|笔|毫秒|秒|分钟)')
    if ($unitMatch.Success) { $unit = $unitMatch.Value } else { $unit = '' }
    return [PSCustomObject]@{ Raw = $Token; Value = $value * $multiplier; IsPercent = $isPercent; Unit = $unit }
}

function Get-EvaluationNumberTokens {
    param([Parameter(Mandatory = $true)][string]$Text)
    $pattern = '(?<![\p{L}\d])[-+]?\d(?:[\d,，\s]*\d)?(?:\.\d+)?\s*(?:万|亿)?\s*(?:元|人民币|家|个|条|笔|毫秒|秒|分钟)?\s*(?:%|％)?'
    $tokens = @()
    foreach ($match in [regex]::Matches($Text, $pattern)) {
        $parsed = ConvertFrom-EvaluationNumberToken -Token $match.Value
        if ($null -ne $parsed) { $tokens += [PSCustomObject]@{ Raw=$parsed.Raw; Value=$parsed.Value; IsPercent=$parsed.IsPercent; Unit=$parsed.Unit; Index=$match.Index } }
    }
    return @($tokens)
}

function Test-EvaluationNumberMatch {
    param([double]$Actual, [double]$Expected, [double]$AbsoluteTolerance = 0)
    return [math]::Abs($Actual - $Expected) -le $AbsoluteTolerance
}

function Get-EvaluationContextWindows {
    param([string]$Text, [object[]]$Terms, [int]$MaxContextChars = 100)
    $windows = @()
    $lines = [regex]::Split($Text, '\r?\n')
    foreach ($termValue in $Terms) {
        $term = [string]$termValue
        if ([string]::IsNullOrWhiteSpace($term)) { continue }
        foreach ($line in $lines) {
            if ($line.IndexOf($term, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) { $windows += $line.Trim() }
        }
        $startAt = 0
        while ($startAt -lt $Text.Length) {
            $index = $Text.IndexOf($term, $startAt, [System.StringComparison]::OrdinalIgnoreCase)
            if ($index -lt 0) { break }
            $left = [math]::Max(0, $index - [math]::Floor($MaxContextChars / 3))
            $length = [math]::Min($Text.Length - $left, $MaxContextChars)
            $windows += $Text.Substring($left, $length).Trim()
            $startAt = $index + [math]::Max(1, $term.Length)
        }
    }
    return @($windows | Select-Object -Unique)
}

function Get-EntityContext {
    param([string]$Text, $Entity, [int]$MaxContextChars = 180)
    $terms = @([string]$Entity.name)
    if ($null -ne $Entity.aliases) { $terms += @($Entity.aliases | ForEach-Object { [string]$_ }) }
    $allWindows = @(Get-EvaluationContextWindows -Text $Text -Terms $terms -MaxContextChars $MaxContextChars)
    $lineWindows = @($allWindows | Where-Object { $_ -notmatch '[\r\n]' -and ($_.Length -lt $MaxContextChars) })
    $windows = if ($lineWindows.Count -gt 0) { $lineWindows } else { $allWindows }
    return [PSCustomObject]@{ Terms=$terms; Windows=@($windows) }
}

function New-AssertionResult {
    param($Assertion, [string]$Status, [AllowNull()]$Actual, [AllowNull()]$Expected, [AllowNull()][string]$FailureReason)
    $passed = if ($Status -eq 'PASSED') { $true } elseif ($Status -eq 'FAILED') { $false } else { $null }
    return [PSCustomObject]@{ assertionId=[string]$Assertion.assertionId; description=[string]$Assertion.description; status=$Status; passed=$passed; actual=$Actual; expected=$Expected; failureReason=$FailureReason }
}

function Invoke-LabelValueAssertion {
    param([string]$Answer, $Assertion)
    $valueType = if ($Assertion.valueType) { [string]$Assertion.valueType } else { 'number' }
    $tolerance = if ($null -ne $Assertion.absoluteTolerance) { [double]$Assertion.absoluteTolerance } else { 0.0 }
    $maxContextChars = if ($Assertion.maxContextChars) { [int]$Assertion.maxContextChars } else { 100 }
    $expected = [double]$Assertion.expected
    $allowedUnitsProperty = $Assertion.PSObject.Properties['allowedUnits']
    $allowedUnits = if ($null -ne $allowedUnitsProperty) { @($allowedUnitsProperty.Value | ForEach-Object { [string]$_ }) } else { $null }
    $contexts = @(Get-EvaluationContextWindows -Text $Answer -Terms @($Assertion.labels) -MaxContextChars $maxContextChars)
    $observed = @()
    foreach ($context in $contexts) {
        foreach ($token in (Get-EvaluationNumberTokens -Text $context)) {
            if ($valueType -eq 'percentage' -and -not $token.IsPercent) { continue }
            if ($valueType -ne 'percentage' -and $token.IsPercent) { continue }
            if ($null -ne $allowedUnits -and $allowedUnits -notcontains [string]$token.Unit) { continue }
            $observed += [PSCustomObject]@{ context=$context; raw=$token.Raw; value=$token.Value }
            if (Test-EvaluationNumberMatch -Actual $token.Value -Expected $expected -AbsoluteTolerance $tolerance) {
                return New-AssertionResult -Assertion $Assertion -Status 'PASSED' -Actual $observed[-1] -Expected $expected -FailureReason $null
            }
        }
    }
    $reason = if ($contexts.Count -eq 0) { '未找到与目标指标相关的语义标签。' } else { '在目标指标附近未提取到符合 Gold 的数值。' }
    return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual $observed -Expected $expected -FailureReason $reason
}

function Invoke-EntityMetricsAssertion {
    param([string]$Answer, $Assertion)
    $actualEntities = @(); $failedEntities = @()
    foreach ($entity in @($Assertion.entities)) {
        $context = Get-EntityContext -Text $Answer -Entity $entity
        $entityResult = [ordered]@{ name=[string]$entity.name; contexts=@($context.Windows); metrics=@() }
        if ($context.Windows.Count -eq 0) { $failedEntities += [string]$entity.name; $actualEntities += [PSCustomObject]$entityResult; continue }
        foreach ($metric in @($entity.metrics)) {
            $expected=[double]$metric.expected; $valueType=if($metric.valueType){[string]$metric.valueType}else{'number'}; $tolerance=if($null-ne$metric.absoluteTolerance){[double]$metric.absoluteTolerance}else{0.0}
            $matched=$false; $seen=@()
            foreach ($window in $context.Windows) { foreach ($token in (Get-EvaluationNumberTokens -Text $window)) {
                if ($valueType -eq 'percentage' -and -not $token.IsPercent) { continue }; if ($valueType -ne 'percentage' -and $token.IsPercent) { continue }
                $seen += $token.Value; if (Test-EvaluationNumberMatch -Actual $token.Value -Expected $expected -AbsoluteTolerance $tolerance) { $matched=$true }
            }}
            $entityResult.metrics += [PSCustomObject]@{ expected=$expected; valueType=$valueType; matched=$matched; observed=@($seen|Select-Object -Unique) }
            if (-not $matched) { $failedEntities += [string]$entity.name }
        }
        $actualEntities += [PSCustomObject]$entityResult
    }
    if ($failedEntities.Count -eq 0) { return New-AssertionResult -Assertion $Assertion -Status 'PASSED' -Actual $actualEntities -Expected @($Assertion.entities) -FailureReason $null }
    return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual $actualEntities -Expected @($Assertion.entities) -FailureReason ("以下实体缺少或数值不匹配："+(($failedEntities|Select-Object -Unique)-join '、'))
}

function Get-EntityExpectedNumber {
    param([string]$Answer, $Entity, [double]$Expected, [double]$Tolerance = 0)
    $context=Get-EntityContext -Text $Answer -Entity $Entity; $observed=@()
    foreach($window in $context.Windows){foreach($token in (Get-EvaluationNumberTokens -Text $window)){if($token.IsPercent){continue};$observed+=$token.Value;if(Test-EvaluationNumberMatch -Actual $token.Value -Expected $Expected -AbsoluteTolerance $Tolerance){return [PSCustomObject]@{matched=$true;value=$token.Value;contexts=@($context.Windows);observed=@($observed|Select-Object -Unique)}}}}
    return [PSCustomObject]@{matched=$false;value=$null;contexts=@($context.Windows);observed=@($observed|Select-Object -Unique)}
}

function Invoke-EntityMetricSumAssertion {
    param([string]$Answer, $Assertion)
    $items=@();$missing=@();$sum=0.0
    foreach($entity in @($Assertion.entities)){$expected=[double]$entity.expected;$match=Get-EntityExpectedNumber -Answer $Answer -Entity $entity -Expected $expected;$items+=[PSCustomObject]@{name=[string]$entity.name;expected=$expected;matched=$match.matched;observed=$match.observed};if($match.matched){$sum+=$expected}else{$missing+=[string]$entity.name}}
    $expectedSum=[double]$Assertion.expectedSum
    if($missing.Count -eq 0 -and (Test-EvaluationNumberMatch -Actual $sum -Expected $expectedSum)){return New-AssertionResult -Assertion $Assertion -Status 'PASSED' -Actual ([PSCustomObject]@{items=$items;sum=$sum}) -Expected $expectedSum -FailureReason $null}
    $reason=if($missing.Count -gt 0){"无法从回答中验证以下分类数量：$($missing -join '、')"}else{"分类数量合计 $sum 与 Gold $expectedSum 不一致。"}
    return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual ([PSCustomObject]@{items=$items;sum=$sum}) -Expected $expectedSum -FailureReason $reason
}

function Split-EvaluationMarkdownRow {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Line)
    $normalizedLine = $Line.Replace([char]0xFF5C, '|').Trim()
    if (-not $normalizedLine.Contains('|')) { return @() }
    return @(($normalizedLine.Trim('|') -split '\|') | ForEach-Object { $_.Trim() })
}

function ConvertFrom-EvaluationIntegerCell {
    param([AllowNull()][string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
    $matches = @([regex]::Matches($Text, '(?<!\d)\d+(?!\d)') | ForEach-Object { [int]$_.Value } | Select-Object -Unique)
    if ($matches.Count -ne 1) { return $null }
    return $matches[0]
}

function Test-EvaluationEntityCellMatch {
    param([string]$Cell, $Entity)
    $normalizedCell = $Cell.Trim()
    $terms = @([string]$Entity.name)
    if ($null -ne $Entity.aliases) { $terms += @($Entity.aliases | ForEach-Object { [string]$_ }) }
    foreach ($term in $terms) {
        if ($normalizedCell.Equals($term.Trim(), [System.StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    return $false
}

function Get-EvaluationTableCountRankEvidence {
    param([string]$Answer, [object[]]$Entities)
    $lines = [regex]::Split($Answer.Replace([char]0xFF5C, '|'), '\r?\n')
    $records = @()
    for ($lineIndex = 0; $lineIndex -lt $lines.Count; $lineIndex++) {
        $header = @(Split-EvaluationMarkdownRow -Line $lines[$lineIndex])
        if ($header.Count -lt 3) { continue }
        $rankIndex = -1; $entityIndex = -1; $countIndex = -1
        for ($columnIndex = 0; $columnIndex -lt $header.Count; $columnIndex++) {
            $cell = $header[$columnIndex]
            if ($rankIndex -lt 0 -and $cell -match '^(排名|名次|竞赛排名)$') { $rankIndex = $columnIndex; continue }
            if ($entityIndex -lt 0 -and $cell -match '^(分类|类别|类型|店铺分类|店铺类型)$') { $entityIndex = $columnIndex; continue }
            if ($countIndex -lt 0 -and $cell -match '^(店铺数|店铺数量|数量|门店数|商铺数)$') { $countIndex = $columnIndex }
        }
        if ($rankIndex -lt 0 -or $entityIndex -lt 0 -or $countIndex -lt 0) { continue }
        for ($rowIndex = $lineIndex + 1; $rowIndex -lt $lines.Count; $rowIndex++) {
            $row = @(Split-EvaluationMarkdownRow -Line $lines[$rowIndex])
            if ($row.Count -eq 0) { break }
            if (@($row | Where-Object { $_ -notmatch '^:?-{3,}:?$' }).Count -eq 0) { continue }
            if ($row.Count -le [math]::Max($rankIndex, [math]::Max($entityIndex, $countIndex))) { break }
            $matchedEntity = $null
            foreach ($entity in $Entities) {
                if (Test-EvaluationEntityCellMatch -Cell $row[$entityIndex] -Entity $entity) { $matchedEntity = $entity; break }
            }
            if ($null -eq $matchedEntity) { continue }
            $records += [PSCustomObject]@{
                name = [string]$matchedEntity.name
                source = 'markdownTable'
                context = $lines[$rowIndex].Trim()
                count = ConvertFrom-EvaluationIntegerCell -Text $row[$countIndex]
                rank = ConvertFrom-EvaluationIntegerCell -Text $row[$rankIndex]
                structured = $true
            }
        }
    }
    return @($records)
}

function Get-EvaluationProseCountRankEvidence {
    param([string]$Answer, $Entity)
    $context = Get-EntityContext -Text $Answer -Entity $Entity -MaxContextChars 220
    $records = @()
    foreach ($window in @($context.Windows)) {
        $countMatch = [regex]::Match($window, '(?<!\d)(\d[\d,，]*)\s*家')
        if (-not $countMatch.Success) {
            $countMatch = [regex]::Match($window, '(?:店铺数|店铺数量|数量|门店数|商铺数)\s*(?:为|是|[:：])?\s*(\d[\d,，]*)')
        }
        $rankMatch = [regex]::Match($window, '(?:排名|名次|竞赛排名)\s*(?:为|是|[:：])?\s*(?:并列\s*)?(?:第\s*)?(\d+)\s*(?:名)?')
        if (-not $rankMatch.Success) { $rankMatch = [regex]::Match($window, '(?:并列\s*)?第\s*(\d+)\s*名') }
        if (-not $countMatch.Success -and -not $rankMatch.Success) { continue }
        $count = if ($countMatch.Success) { [int](($countMatch.Groups[1].Value) -replace '[,，]', '') } else { $null }
        $rank = if ($rankMatch.Success) { [int]$rankMatch.Groups[1].Value } else { $null }
        $records += [PSCustomObject]@{ name=[string]$Entity.name; source='prose'; context=$window; count=$count; rank=$rank; structured=$false }
    }
    return @($records)
}

function Invoke-EntityCountRanksAssertion {
    param([string]$Answer, $Assertion)
    $entities = @($Assertion.entities)
    $tableEvidence = @(Get-EvaluationTableCountRankEvidence -Answer $Answer -Entities $entities)
    $actual = @(); $failed = @(); $unverifiable = @()
    foreach ($entity in $entities) {
        $expectedCount = [int]$entity.expectedCount; $expectedRank = [int]$entity.expectedRank
        $evidence = @($tableEvidence | Where-Object { $_.name -eq [string]$entity.name })
        if ($evidence.Count -eq 0) { $evidence = @(Get-EvaluationProseCountRankEvidence -Answer $Answer -Entity $entity) }
        $entityActual = [ordered]@{ name=[string]$entity.name; expectedCount=$expectedCount; expectedRank=$expectedRank; evidence=$evidence; matched=$false }
        if ($evidence.Count -eq 0) {
            $context = Get-EntityContext -Text $Answer -Entity $entity
            if ($context.Windows.Count -eq 0) { $failed += ([string]$entity.name + '（分类缺失）') }
            else { $unverifiable += ([string]$entity.name + '（无法可靠提取数量与排名）') }
            $actual += [PSCustomObject]$entityActual
            continue
        }
        $structuredIncomplete = @($evidence | Where-Object { $_.structured -and ($null -eq $_.count -or $null -eq $_.rank) })
        $proseIncomplete = @($evidence | Where-Object { -not $_.structured -and ($null -eq $_.count -or $null -eq $_.rank) })
        $incorrect = @($evidence | Where-Object { $null -ne $_.count -and $null -ne $_.rank -and ($_.count -ne $expectedCount -or $_.rank -ne $expectedRank) })
        $correct = @($evidence | Where-Object { $null -ne $_.count -and $null -ne $_.rank -and $_.count -eq $expectedCount -and $_.rank -eq $expectedRank })
        if ($incorrect.Count -gt 0 -or $structuredIncomplete.Count -gt 0) { $failed += [string]$entity.name }
        elseif ($correct.Count -gt 0) { $entityActual.matched = $true }
        elseif ($proseIncomplete.Count -gt 0) { $unverifiable += ([string]$entity.name + '（非结构化表达无法可靠绑定数量与排名）') }
        else { $unverifiable += [string]$entity.name }
        $actual += [PSCustomObject]$entityActual
    }
    if ($failed.Count -gt 0) {
        return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual $actual -Expected $entities -FailureReason ("以下分类缺失、数量不匹配、排名不匹配或缺少结构化排名：" + (($failed | Select-Object -Unique) -join '、'))
    }
    if ($unverifiable.Count -gt 0) {
        return New-AssertionResult -Assertion $Assertion -Status 'UNVERIFIABLE' -Actual $actual -Expected $entities -FailureReason ("无法可靠自动判断以下分类的数量与排名绑定：" + (($unverifiable | Select-Object -Unique) -join '、'))
    }
    return New-AssertionResult -Assertion $Assertion -Status 'PASSED' -Actual $actual -Expected $entities -FailureReason $null
}

function Find-EntityPosition {
    param([string]$Text, $Entity)
    $terms=@([string]$Entity.name);if($null-ne$Entity.aliases){$terms+=@($Entity.aliases|ForEach-Object{[string]$_})};$positions=@()
    foreach($term in $terms){$index=$Text.IndexOf($term,[System.StringComparison]::OrdinalIgnoreCase);if($index-ge 0){$positions+=$index}}
    if($positions.Count-eq 0){return -1};return ($positions|Measure-Object -Minimum).Minimum
}

function Invoke-OrderedEntitiesAssertion {
    param([string]$Answer, $Assertion)
    $items=@();$previous=-1;$passed=$true
    foreach($entity in @($Assertion.entities)){$position=Find-EntityPosition -Text $Answer -Entity $entity;$items+=[PSCustomObject]@{name=[string]$entity.name;position=$position};if($position-lt 0-or$position-le$previous){$passed=$false};if($position-ge 0){$previous=$position}}
    if($passed){return New-AssertionResult -Assertion $Assertion -Status 'PASSED' -Actual $items -Expected @($Assertion.entities|ForEach-Object{$_.name}) -FailureReason $null}
    return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual $items -Expected @($Assertion.entities|ForEach-Object{$_.name}) -FailureReason '实体缺失或在回答中的出现顺序与 Gold 不一致。'
}

function Invoke-OrderedEntityMetricsAssertion {
    param([string]$Answer, $Assertion)
    $orderResult=Invoke-OrderedEntitiesAssertion -Answer $Answer -Assertion $Assertion;$items=@();$failed=@()
    foreach($entity in @($Assertion.entities)){$expected=[double]$entity.expected;$tolerance=if($null-ne$entity.absoluteTolerance){[double]$entity.absoluteTolerance}else{0.0};$match=Get-EntityExpectedNumber -Answer $Answer -Entity $entity -Expected $expected -Tolerance $tolerance;$items+=[PSCustomObject]@{name=[string]$entity.name;expected=$expected;matched=$match.matched;observed=$match.observed};if(-not$match.matched){$failed+=[string]$entity.name}}
    if($orderResult.status-eq'PASSED'-and$failed.Count-eq 0){return New-AssertionResult -Assertion $Assertion -Status 'PASSED' -Actual ([PSCustomObject]@{order=$orderResult.actual;metrics=$items}) -Expected @($Assertion.entities) -FailureReason $null}
    $reasons=@();if($orderResult.status-ne'PASSED'){$reasons+=$orderResult.failureReason};if($failed.Count-gt 0){$reasons+=("金额不匹配："+($failed-join'、'))}
    return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual ([PSCustomObject]@{order=$orderResult.actual;metrics=$items}) -Expected @($Assertion.entities) -FailureReason ($reasons-join' ')
}

function Invoke-TermAssertion {
    param([string]$Answer, $Assertion, [bool]$Forbidden)
    $matches=@();foreach($termValue in @($Assertion.terms)){
        $term=[string]$termValue;$startAt=0
        while($startAt-lt$Answer.Length){
            $index=$Answer.IndexOf($term,$startAt,[System.StringComparison]::OrdinalIgnoreCase);if($index-lt 0){break}
            $negated=$false
            $negationProperty = $Assertion.PSObject.Properties['negationPrefixes']
            if($Forbidden-and$null-ne$negationProperty){
                $prefixStart=[math]::Max(0,$index-8);$prefix=$Answer.Substring($prefixStart,$index-$prefixStart)
                foreach($negation in @($negationProperty.Value)){if($prefix.EndsWith([string]$negation,[System.StringComparison]::OrdinalIgnoreCase)){$negated=$true;break}}
            }
            if(-not$negated){$matches+=$term;break}
            $startAt=$index+[math]::Max(1,$term.Length)
        }
    }
    if($Forbidden){if($matches.Count-eq 0){return New-AssertionResult -Assertion $Assertion -Status 'PASSED' -Actual @() -Expected ("不得包含："+(@($Assertion.terms)-join'、')) -FailureReason $null};return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual $matches -Expected ("不得包含："+(@($Assertion.terms)-join'、')) -FailureReason ("发现禁用表达："+($matches-join'、'))}
    $mode=if($Assertion.match){[string]$Assertion.match}else{'any'};$passed=if($mode-eq'all'){$matches.Count-eq@($Assertion.terms).Count}else{$matches.Count-gt 0}
    if($passed){return New-AssertionResult -Assertion $Assertion -Status 'PASSED' -Actual $matches -Expected @($Assertion.terms) -FailureReason $null};return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual $matches -Expected @($Assertion.terms) -FailureReason '未找到要求的限定表达。'
}

function Invoke-DataPilotAssertions {
    param([string]$Answer, [object[]]$Assertions)
    $normalized=ConvertTo-NormalizedEvaluationText -Text $Answer;$results=@()
    foreach($assertion in $Assertions){try{switch([string]$assertion.type){
        'labelValue'{$results+=Invoke-LabelValueAssertion -Answer $normalized -Assertion $assertion}
        'entityMetrics'{$results+=Invoke-EntityMetricsAssertion -Answer $normalized -Assertion $assertion}
        'entityMetricSum'{$results+=Invoke-EntityMetricSumAssertion -Answer $normalized -Assertion $assertion}
        'entityCountRanks'{$results+=Invoke-EntityCountRanksAssertion -Answer $normalized -Assertion $assertion}
        'orderedEntities'{$results+=Invoke-OrderedEntitiesAssertion -Answer $normalized -Assertion $assertion}
        'orderedEntityMetrics'{$results+=Invoke-OrderedEntityMetricsAssertion -Answer $normalized -Assertion $assertion}
        'requiredTerms'{$results+=Invoke-TermAssertion -Answer $normalized -Assertion $assertion -Forbidden $false}
        'forbiddenTerms'{$results+=Invoke-TermAssertion -Answer $normalized -Assertion $assertion -Forbidden $true}
        'manual'{$results+=New-AssertionResult -Assertion $assertion -Status 'MANUAL' -Actual $null -Expected $assertion.expected -FailureReason '该语义规则无法由 V1 字符串与数字规则可靠验证。'}
        default{$results+=New-AssertionResult -Assertion $assertion -Status 'UNVERIFIABLE' -Actual $null -Expected $null -FailureReason ("不支持的断言类型："+[string]$assertion.type)}
    }}catch{$results+=New-AssertionResult -Assertion $assertion -Status 'UNVERIFIABLE' -Actual $null -Expected $null -FailureReason $_.Exception.Message}}
    return @($results)
}

function Get-DataPilotCaseStatus {
    param([bool]$HttpSuccess, [object[]]$AssertionResults)
    if(-not$HttpSuccess){return 'HTTP_FAILED'}
    if(@($AssertionResults|Where-Object{$_.status-eq'FAILED'}).Count-gt 0){return 'FAILED'}
    if(@($AssertionResults|Where-Object{$_.status-in@('MANUAL','UNVERIFIABLE')}).Count-gt 0){return 'NEEDS_MANUAL_REVIEW'}
    return 'PASSED'
}
