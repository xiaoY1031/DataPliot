Set-StrictMode -Version 2.0

function ConvertTo-NormalizedEvaluationText {
    param([AllowNull()][string]$Text)
    if ($null -eq $Text) { return "" }
    return $Text.Replace([char]0x00A0, ' ').Replace([char]0x3000, ' ') -replace '\*\*|__', ''
}

function ConvertTo-EvaluationMatchKey {
    param([AllowNull()][string]$Text)
    if ($null -eq $Text) { return '' }
    $collapsed = $Text -replace '\s+', ''
    if ($collapsed.Length -eq 0) { return '' }
    $builder = New-Object System.Text.StringBuilder
    foreach ($ch in $collapsed.ToCharArray()) {
        $code = [int]$ch
        if ($code -ge 0xFF01 -and $code -le 0xFF5E) { $code = $code - 0xFEE0 }
        [void]$builder.Append([char]$code)
    }
    return $builder.ToString().ToLowerInvariant()
}

function Test-EvaluationSubsequenceMatch {
    param([Parameter(Mandatory = $true)][string]$TermKey, [Parameter(Mandatory = $true)][string]$TextKey)
    if ($TermKey.Length -eq 0 -or $TermKey.Length -gt $TextKey.Length) { return $false }
    $gapMax = [math]::Max(2, [math]::Floor($TermKey.Length / 2))
    $ti = 0; $skipped = 0
    for ($si = 0; $si -lt $TextKey.Length; $si++) {
        if ($TextKey[$si] -eq $TermKey[$ti]) {
            $ti++; $skipped = 0
            if ($ti -ge $TermKey.Length) { return $true }
        } elseif ($ti -gt 0) {
            $skipped++
            if ($skipped -gt $gapMax) { return $false }
        }
    }
    return $false
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

function Get-EvaluationLabelWindows {
    param([string]$Text, [object[]]$Terms, [int]$MaxContextChars = 100)
    $windows = @(Get-EvaluationContextWindows -Text $Text -Terms $Terms -MaxContextChars $MaxContextChars)
    $lines = [regex]::Split($Text, '\r?\n')
    foreach ($termValue in $Terms) {
        $term = [string]$termValue
        if ([string]::IsNullOrWhiteSpace($term)) { continue }
        $termKey = ConvertTo-EvaluationMatchKey -Text $term
        if ($termKey.Length -eq 0) { continue }
        foreach ($line in $lines) {
            $lineKey = ConvertTo-EvaluationMatchKey -Text $line
            if ($lineKey.Length -eq 0) { continue }
            if ($lineKey.IndexOf($termKey, [System.StringComparison]::Ordinal) -ge 0) { $windows += $line.Trim(); continue }
            if (Test-EvaluationSubsequenceMatch -TermKey $termKey -TextKey $lineKey) { $windows += $line.Trim() }
        }
    }
    return @($windows | Select-Object -Unique)
}

function Get-LabeledEntityWindows {
    param([string]$Text, [object[]]$LabelTerms, [string]$EntityPattern)
    $lines = [regex]::Split($Text, '\r?\n')
    $windows = @()
    foreach ($termValue in $LabelTerms) {
        $term = [string]$termValue
        if ([string]::IsNullOrWhiteSpace($term)) { continue }
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i].IndexOf($term, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) { continue }
            $window = $lines[$i].Trim()
            if (-not [string]::IsNullOrWhiteSpace($EntityPattern) -and -not [regex]::IsMatch($lines[$i], $EntityPattern)) {
                if ($i + 1 -lt $lines.Count -and -not [string]::IsNullOrWhiteSpace($lines[$i + 1])) {
                    $window = $window + [char]10 + $lines[$i + 1].Trim()
                }
            }
            $windows += $window
        }
    }
    return @($windows | Select-Object -Unique)
}

function Get-EvaluationAmbiguousCandidates {
    param([object[]]$Header, [string]$ColumnName)
    $colKey = ConvertTo-EvaluationMatchKey -Text $ColumnName
    if ($colKey.Length -eq 0) { return @() }
    $candidates = @()
    foreach ($h in @($Header)) {
        $hText = [string]$h
        $hKey = ConvertTo-EvaluationMatchKey -Text $hText
        if ($hKey.Length -eq 0) { continue }
        if ($hKey -eq $colKey -or $hKey.IndexOf($colKey, [System.StringComparison]::Ordinal) -ge 0) { $candidates += $hText }
    }
    return @($candidates | Select-Object -Unique)
}

function Test-EvaluationHeaderContains {
    param([object[]]$Header, [string]$ColumnName)
    $colKey = ConvertTo-EvaluationMatchKey -Text $ColumnName
    if ($colKey.Length -eq 0) { return $false }
    foreach ($h in @($Header)) {
        $hKey = ConvertTo-EvaluationMatchKey -Text ([string]$h)
        if ($hKey.Length -eq 0) { continue }
        if ($hKey -eq $colKey -or $hKey.IndexOf($colKey, [System.StringComparison]::Ordinal) -ge 0) { return $true }
    }
    return $false
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
    $contexts = @(Get-EvaluationLabelWindows -Text $Answer -Terms @($Assertion.labels) -MaxContextChars $maxContextChars)
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
    $cellKey = ConvertTo-EvaluationMatchKey -Text $Cell
    $terms = @([string]$Entity.name)
    if ($null -ne $Entity.aliases) { $terms += @($Entity.aliases | ForEach-Object { [string]$_ }) }
    foreach ($term in $terms) {
        if ($cellKey -eq (ConvertTo-EvaluationMatchKey -Text $term)) { return $true }
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

function Get-EvaluationProperty {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function ConvertFrom-EvaluationCellValue {
    param([AllowNull()][string]$Cell, [string]$ValueType)
    $text = if ($null -eq $Cell) { '' } else { $Cell.Trim() }
    switch ($ValueType) {
        'string' { return $text }
        'integer' {
            $tokens = @(Get-EvaluationNumberTokens -Text $text)
            foreach ($t in $tokens) { if (-not $t.IsPercent) { return [double]$t.Value } }
            return $null
        }
        'decimal' {
            $tokens = @(Get-EvaluationNumberTokens -Text $text)
            foreach ($t in $tokens) { if (-not $t.IsPercent) { return [double]$t.Value } }
            return $null
        }
        'percentage' {
            $tokens = @(Get-EvaluationNumberTokens -Text $text)
            foreach ($t in $tokens) { if ($t.IsPercent) { return [double]$t.Value } }
            return $null
        }
        'currency' {
            $unit = 'yuan'
            if ($text -match '分' -and $text -notmatch '百分|评分|分钟|%|％') { $unit = 'cent' }
            $tokens = @(Get-EvaluationNumberTokens -Text $text)
            if ($tokens.Count -eq 0) { return $null }
            return [PSCustomObject]@{ Value = [double]$tokens[0].Value; Unit = $unit }
        }
        'date' {
            $m = [regex]::Match($text, '\d{4}-\d{2}-\d{2}')
            if ($m.Success) { return $m.Value }
            return $null
        }
        'null' {
            if ([string]::IsNullOrWhiteSpace($text) -or $text -match '^(NULL|null|-|N/A|无|空|—)$') { return $null }
            return $text
        }
        default { return $text }
    }
}

function Test-EvaluationCurrencyCellMatch {
    param($Actual, [double]$Expected, [double]$Tolerance, [string]$CurrencyUnit, [bool]$AllowUnitConversion)
    if ($null -eq $Actual) { return $false }
    $v = [double]$Actual.Value
    $u = [string]$Actual.Unit
    if ($AllowUnitConversion) {
        $actualYuan = if ($u -eq 'cent') { $v / 100.0 } else { $v }
        $expectedYuan = if ($CurrencyUnit -eq 'cent') { $Expected / 100.0 } else { $Expected }
        return (Test-EvaluationNumberMatch -Actual $actualYuan -Expected $expectedYuan -AbsoluteTolerance $Tolerance)
    }
    if ($u -ne $CurrencyUnit) { return $false }
    return (Test-EvaluationNumberMatch -Actual $v -Expected $Expected -AbsoluteTolerance $Tolerance)
}

function Test-EvaluationCellMatch {
    param($Actual, $Expected, $Column, [bool]$ForKey)
    $valueType = if ($null -ne (Get-EvaluationProperty -Object $Column -Name 'valueType')) { [string](Get-EvaluationProperty -Object $Column -Name 'valueType') } else { 'string' }
    $matchMode = if ($null -ne (Get-EvaluationProperty -Object $Column -Name 'match')) { [string](Get-EvaluationProperty -Object $Column -Name 'match') } else { 'exact' }
    $tolerance = if ($null -ne (Get-EvaluationProperty -Object $Column -Name 'absoluteTolerance')) { [double](Get-EvaluationProperty -Object $Column -Name 'absoluteTolerance') } else { 0.0 }
    switch ($valueType) {
        'string' {
            $actualKey = if ($null -eq $Actual) { '' } else { ConvertTo-EvaluationMatchKey -Text ([string]$Actual) }
            $expectedKey = if ($null -eq $Expected) { '' } else { ConvertTo-EvaluationMatchKey -Text ([string]$Expected) }
            if ($matchMode -eq 'contains') { return ($actualKey.IndexOf($expectedKey, [System.StringComparison]::Ordinal) -ge 0) }
            return ($actualKey -eq $expectedKey)
        }
        'integer' {
            if ($null -eq $Actual) { return ($null -eq $Expected) }
            return (Test-EvaluationNumberMatch -Actual ([double]$Actual) -Expected ([double]$Expected) -AbsoluteTolerance $tolerance)
        }
        'decimal' {
            if ($null -eq $Actual) { return ($null -eq $Expected) }
            return (Test-EvaluationNumberMatch -Actual ([double]$Actual) -Expected ([double]$Expected) -AbsoluteTolerance $tolerance)
        }
        'percentage' {
            if ($null -eq $Actual) { return ($null -eq $Expected) }
            return (Test-EvaluationNumberMatch -Actual ([double]$Actual) -Expected ([double]$Expected) -AbsoluteTolerance $tolerance)
        }
        'currency' {
            $unit = if ($null -ne (Get-EvaluationProperty -Object $Column -Name 'currencyUnit')) { [string](Get-EvaluationProperty -Object $Column -Name 'currencyUnit') } else { 'yuan' }
            $conv = if ($null -ne (Get-EvaluationProperty -Object $Column -Name 'allowUnitConversion')) { [bool](Get-EvaluationProperty -Object $Column -Name 'allowUnitConversion') } else { $false }
            return (Test-EvaluationCurrencyCellMatch -Actual $Actual -Expected ([double]$Expected) -Tolerance $tolerance -CurrencyUnit $unit -AllowUnitConversion $conv)
        }
        'date' {
            if ($null -eq $Actual) { return $false }
            return ([string]$Actual -eq [string]$Expected)
        }
        'null' {
            $expectNull = ($null -eq $Expected) -or ([string]$Expected -in @('', 'null', 'NULL'))
            if ($expectNull) { return ($null -eq $Actual) }
            return ($null -ne $Actual -and ([string]$Actual -eq [string]$Expected))
        }
    }
    return $false
}

function Split-EvaluationDataTableRow {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Line)
    $trimmed = $Line.Trim()
    if (-not $trimmed.Contains('|')) { return @() }
    return @(($trimmed.Trim('|') -split '\|') | ForEach-Object { $_.Trim() })
}

function Get-EvaluationMarkdownTables {
    param([string]$Answer)
    $lines = [regex]::Split($Answer, '\r?\n')
    $tables = @()
    $i = 0
    while ($i -lt $lines.Count) {
        $header = @(Split-EvaluationDataTableRow -Line $lines[$i])
        if ($header.Count -lt 1) { $i++; continue }
        $isSeparator = $false
        if ($i + 1 -lt $lines.Count) {
            $sepCells = @(Split-EvaluationDataTableRow -Line $lines[$i + 1])
            if ($sepCells.Count -ge 1 -and @($sepCells | Where-Object { $_ -notmatch '^:?-{3,}:?$' }).Count -eq 0) { $isSeparator = $true }
        }
        if (-not $isSeparator) { $i++; continue }
        $rows = @()
        for ($j = $i + 2; $j -lt $lines.Count; $j++) {
            $r = @(Split-EvaluationDataTableRow -Line $lines[$j])
            if ($r.Count -eq 0) { break }
            if (@($r | Where-Object { $_ -notmatch '^:?-{3,}:?$' }).Count -eq 0) { continue }
            $rows += ,$r
        }
        $tables += [PSCustomObject]@{ header = $header; rows = $rows }
        $i += 2 + $rows.Count
    }
    return @($tables)
}

function Find-EvaluationTargetTable {
    param([object[]]$Tables, [object[]]$HeaderAliases, [object[]]$RequiredColumns)
    $tablesArr = @($Tables)
    if ($tablesArr.Count -eq 0) { return [PSCustomObject]@{ Table = $null; Reason = 'none'; Candidates = @() } }
    $required = @()
    if ($null -ne $HeaderAliases -and @($HeaderAliases).Count -gt 0) {
        $required = @($HeaderAliases | ForEach-Object { [string]$_ })
    } elseif ($null -ne $RequiredColumns -and @($RequiredColumns).Count -gt 0) {
        $required = @($RequiredColumns | ForEach-Object { [string]$_ })
    }
    if ($required.Count -eq 0) { return [PSCustomObject]@{ Table = $tablesArr[0]; Reason = 'default'; Candidates = @() } }
    $matches = @()
    foreach ($t in $tablesArr) {
        $allMatch = $true
        foreach ($r in $required) {
            if (-not (Test-EvaluationHeaderContains -Header @($t.header) -ColumnName $r)) { $allMatch = $false; break }
        }
        if ($allMatch) { $matches += $t }
    }
    if ($matches.Count -eq 1) { return [PSCustomObject]@{ Table = $matches[0]; Reason = 'unique'; Candidates = @() } }
    if ($matches.Count -gt 1) { return [PSCustomObject]@{ Table = $null; Reason = 'ambiguous'; Candidates = $matches } }
    return [PSCustomObject]@{ Table = $null; Reason = 'none'; Candidates = @() }
}

function Find-EvaluationColumnIndex {
    param([object[]]$Header, [string]$ColumnName)
    $colKey = ConvertTo-EvaluationMatchKey -Text $ColumnName
    if ($colKey.Length -eq 0) { return -1 }
    $exact = @(); $substring = @()
    for ($i = 0; $i -lt @($Header).Count; $i++) {
        $hKey = ConvertTo-EvaluationMatchKey -Text ([string]$Header[$i])
        if ($hKey.Length -eq 0) { continue }
        if ($hKey -eq $colKey) { $exact += $i }
        elseif ($hKey.IndexOf($colKey, [System.StringComparison]::Ordinal) -ge 0) { $substring += $i }
    }
    if ($exact.Count -eq 1) { return $exact[0] }
    if ($exact.Count -gt 1) { return -2 }
    if ($substring.Count -eq 1) { return $substring[0] }
    if ($substring.Count -gt 1) {
        $best = @(); $minExtra = -1
        foreach ($i in $substring) {
            $hKey = ConvertTo-EvaluationMatchKey -Text ([string]$Header[$i])
            $extra = $hKey.Length - $colKey.Length
            if ($minExtra -lt 0 -or $extra -lt $minExtra) { $minExtra = $extra; $best = @($i) }
            elseif ($extra -eq $minExtra) { $best += $i }
        }
        if ($best.Count -eq 1) { return $best[0] }
        return -2
    }
    return -1
}

function Test-EvaluationRowKeyMatch {
    param($Record, $ExpectedRow, [object[]]$KeyColumns, [object[]]$Columns)
    foreach ($kc in $KeyColumns) {
        $col = @($Columns | Where-Object { [string]$_.name -eq [string]$kc } | Select-Object -First 1)
        if (@($col).Count -eq 0) { return $false }
        $colObj = @($col)[0]
        $actualVal = Get-EvaluationProperty -Object $Record -Name ([string]$kc)
        $expectedVal = Get-EvaluationProperty -Object $ExpectedRow -Name ([string]$kc)
        if (-not (Test-EvaluationCellMatch -Actual $actualVal -Expected $expectedVal -Column $colObj -ForKey $true)) { return $false }
    }
    return $true
}

function Get-EvaluationNumericValue {
    param($Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [PSCustomObject]) {
        $vprop = $Value.PSObject.Properties['Value']
        if ($null -ne $vprop) { return [double]$vprop.Value }
    }
    return [double]$Value
}

function Test-EvaluationCompetitionRanks {
    param([object[]]$Records, [string]$RankColumn, [string]$MetricColumn)
    $n = @($Records).Count
    if ($n -eq 0) { return $true }
    $metrics = @(); $ranks = @()
    foreach ($r in @($Records)) {
        $mv = Get-EvaluationNumericValue -Value (Get-EvaluationProperty -Object $r -Name $MetricColumn)
        $rv = Get-EvaluationNumericValue -Value (Get-EvaluationProperty -Object $r -Name $RankColumn)
        if ($null -eq $mv -or $null -eq $rv) { return $false }
        $metrics += [double]$mv
        $ranks += [double]$rv
    }
    for ($i = 0; $i -lt $n; $i++) {
        $expectedRank = 1
        for ($j = 0; $j -lt $n; $j++) {
            if ($j -ne $i -and $metrics[$j] -gt $metrics[$i]) { $expectedRank++ }
        }
        if ([math]::Abs($ranks[$i] - $expectedRank) -gt 1e-9) { return $false }
    }
    return $true
}

function Invoke-TabularRowsAssertion {
    param([string]$Answer, $Assertion)
    $columns = @($Assertion.columns)
    $expectedRows = @($Assertion.expectedRows)
    $keyColumns = @(if ($null -ne $Assertion.PSObject.Properties['keyColumns']) { @($Assertion.keyColumns | ForEach-Object { [string]$_ }) } else { @() })
    $mode = if ($null -ne $Assertion.PSObject.Properties['mode']) { [string]$Assertion.mode } else { 'exact' }
    $ordered = if ($null -ne $Assertion.PSObject.Properties['ordered']) { [bool]$Assertion.ordered } else { $false }
    $expectedRowCount = if ($null -ne $Assertion.PSObject.Properties['expectedRowCount']) { [int]$Assertion.expectedRowCount } else { $expectedRows.Count }
    $headerAliases = @(if ($null -ne $Assertion.PSObject.Properties['headerAliases']) { @($Assertion.headerAliases | ForEach-Object { [string]$_ }) } else { @() })

    $tables = @(Get-EvaluationMarkdownTables -Answer $Answer)
    if ($tables.Count -eq 0) {
        return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual $null -Expected $expectedRows -FailureReason '回答中未找到 Markdown 表格。'
    }
    $requiredColumns = @($columns | ForEach-Object { [string]$_.name })
    $tableResult = Find-EvaluationTargetTable -Tables $tables -HeaderAliases $headerAliases -RequiredColumns $requiredColumns
    if ($tableResult.Reason -eq 'none') {
        $label = if ($headerAliases.Count -gt 0) { $headerAliases } else { $requiredColumns }
        return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual $null -Expected $expectedRows -FailureReason ('未找到同时包含所需列（' + ($label -join '、') + '）的表格。')
    }
    if ($tableResult.Reason -eq 'ambiguous') {
        return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual $null -Expected $expectedRows -FailureReason '存在多张同时包含所需列的表格，无法唯一确定目标表。'
    }
    $table = $tableResult.Table
    $colIndex = @{}
    foreach ($col in $columns) {
        $colName = [string]$col.name
        $idx = Find-EvaluationColumnIndex -Header @($table.header) -ColumnName $colName
        if ($idx -eq -2) {
            $candidates = @(Get-EvaluationAmbiguousCandidates -Header @($table.header) -ColumnName $colName)
            return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual $null -Expected $expectedRows -FailureReason ("表头列歧义：$colName 可匹配多个列（" + ($candidates -join '、') + '）。')
        }
        if ($idx -lt 0) {
            return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual $null -Expected $expectedRows -FailureReason ("未找到表头列：$colName")
        }
        $colIndex[$colName] = $idx
    }
    $records = @()
    foreach ($row in @($table.rows)) {
        $recProps = [ordered]@{}
        foreach ($col in $columns) {
            $colName = [string]$col.name
            $idx = $colIndex[$colName]
            $cell = if ($idx -lt @($row).Count) { $row[$idx] } else { '' }
            $recProps[$colName] = ConvertFrom-EvaluationCellValue -Cell $cell -ValueType ([string]$col.valueType)
        }
        $records += [PSCustomObject]$recProps
    }

    if ($null -ne $Assertion.PSObject.Properties['rankMode'] -and [string]$Assertion.rankMode -eq 'competition') {
        $rankCol = [string]$Assertion.rankColumn
        $metricCol = [string]$Assertion.metricColumn
        if (-not (Test-EvaluationCompetitionRanks -Records $records -RankColumn $rankCol -MetricColumn $metricCol)) {
            return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual $records -Expected $expectedRows -FailureReason '竞赛排名与指标值不一致（并列未同排名或未跳号）。'
        }
    }

    $usedRecordIndexes = @()
    $matchedRecordIndexes = @()
    $missingExpected = @()
    $mismatchedExpected = @()
    foreach ($er in $expectedRows) {
        $foundRecordIndex = -1
        $mismatchReason = $null
        for ($ri = 0; $ri -lt $records.Count; $ri++) {
            if ($usedRecordIndexes -contains $ri) { continue }
            if (Test-EvaluationRowKeyMatch -Record $records[$ri] -ExpectedRow $er -KeyColumns $keyColumns -Columns $columns) {
                $foundRecordIndex = $ri
                $badColumns = @()
                foreach ($col in $columns) {
                    $colName = [string]$col.name
                    $erProp = $er.PSObject.Properties[$colName]
                    if ($null -eq $erProp) { continue }
                    $actualVal = Get-EvaluationProperty -Object $records[$ri] -Name $colName
                    $expectedVal = $erProp.Value
                    if (-not (Test-EvaluationCellMatch -Actual $actualVal -Expected $expectedVal -Column $col -ForKey $false)) {
                        $badColumns += $colName
                    }
                }
                if ($badColumns.Count -gt 0) { $mismatchReason = '列不匹配：' + ($badColumns -join '、') }
                break
            }
        }
        if ($foundRecordIndex -lt 0) { $missingExpected += $er }
        else {
            $usedRecordIndexes += $foundRecordIndex
            $matchedRecordIndexes += $foundRecordIndex
            if ($null -ne $mismatchReason) { $mismatchedExpected += [PSCustomObject]@{ row = $er; reason = $mismatchReason } }
        }
    }
    $extraRecordIndexes = @()
    for ($ri = 0; $ri -lt $records.Count; $ri++) {
        if ($usedRecordIndexes -notcontains $ri) { $extraRecordIndexes += $ri }
    }
    $orderFailed = $false
    if ($ordered -and $matchedRecordIndexes.Count -gt 1) {
        for ($k = 1; $k -lt $matchedRecordIndexes.Count; $k++) {
            if ($matchedRecordIndexes[$k] -le $matchedRecordIndexes[$k - 1]) { $orderFailed = $true; break }
        }
    }
    $reasons = @()
    if ($missingExpected.Count -gt 0) {
        $missingNames = @($missingExpected | ForEach-Object { $v = Get-EvaluationProperty -Object $_ -Name ([string]$keyColumns[0]); if ($null -eq $v) { '(未知)' } else { [string]$v } })
        $reasons += ('漏行：' + ($missingNames -join '、'))
    }
    if ($mode -eq 'exact') {
        if ($records.Count -ne $expectedRowCount) { $reasons += ('行数不符：实际 ' + $records.Count + ' 行，期望 ' + $expectedRowCount + ' 行') }
        if ($extraRecordIndexes.Count -gt 0) { $reasons += ('额外行：' + $extraRecordIndexes.Count + ' 行') }
    }
    if ($mismatchedExpected.Count -gt 0) { $reasons += ('数值列不匹配：' + (($mismatchedExpected | ForEach-Object { $_.reason }) -join '；')) }
    if ($orderFailed) { $reasons += '行顺序与 Gold 不一致。' }
    if ($reasons.Count -gt 0) {
        return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual $records -Expected $expectedRows -FailureReason ($reasons -join ' ')
    }
    return New-AssertionResult -Assertion $Assertion -Status 'PASSED' -Actual $records -Expected $expectedRows -FailureReason $null
}

function Invoke-LabeledEntitySetAssertion {
    param([string]$Answer, $Assertion)
    $labelTerms = @(if ($null -ne $Assertion.PSObject.Properties['labelTerms']) { @($Assertion.labelTerms | ForEach-Object { [string]$_ }) } else { @() })
    $entityPattern = if ($null -ne $Assertion.PSObject.Properties['entityPattern']) { [string]$Assertion.entityPattern } else { '' }
    $expectedEntities = @(if ($null -ne $Assertion.PSObject.Properties['expectedEntities']) { @($Assertion.expectedEntities | ForEach-Object { [string]$_ }) } else { @() })
    $mode = if ($null -ne $Assertion.PSObject.Properties['mode']) { [string]$Assertion.mode } else { 'exact' }
    $maxContextChars = if ($null -ne $Assertion.PSObject.Properties['maxContextChars']) { [int]$Assertion.maxContextChars } else { 300 }

    if ($entityPattern -eq '' -or $labelTerms.Count -eq 0) {
        return New-AssertionResult -Assertion $Assertion -Status 'UNVERIFIABLE' -Actual $null -Expected $expectedEntities -FailureReason 'labeledEntitySet 缺少 labelTerms 或 entityPattern。'
    }
    $windows = @(Get-LabeledEntityWindows -Text $Answer -LabelTerms $labelTerms -EntityPattern $entityPattern)
    if ($windows.Count -eq 0) {
        return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual $null -Expected $expectedEntities -FailureReason ('未找到目标标签：' + ($labelTerms -join '、'))
    }
    $found = @()
    foreach ($w in $windows) {
        foreach ($m in [regex]::Matches($w, $entityPattern)) {
            if ($found -notcontains $m.Value) { $found += $m.Value }
        }
    }
    $missing = @($expectedEntities | Where-Object { $found -notcontains $_ })
    $extra = @($found | Where-Object { $expectedEntities -notcontains $_ })
    if ($mode -eq 'contains') {
        if ($missing.Count -eq 0) { return New-AssertionResult -Assertion $Assertion -Status 'PASSED' -Actual $found -Expected $expectedEntities -FailureReason $null }
        return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual $found -Expected $expectedEntities -FailureReason ('缺失：' + ($missing -join '、'))
    }
    if ($missing.Count -eq 0 -and $extra.Count -eq 0) { return New-AssertionResult -Assertion $Assertion -Status 'PASSED' -Actual $found -Expected $expectedEntities -FailureReason $null }
    $reasons = @()
    if ($missing.Count -gt 0) { $reasons += '缺失：' + ($missing -join '、') }
    if ($extra.Count -gt 0) { $reasons += '多余：' + ($extra -join '、') }
    return New-AssertionResult -Assertion $Assertion -Status 'FAILED' -Actual $found -Expected $expectedEntities -FailureReason ($reasons -join ' ')
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
        'tabularRows'{$results+=Invoke-TabularRowsAssertion -Answer $normalized -Assertion $assertion}
        'labeledEntitySet'{$results+=Invoke-LabeledEntitySetAssertion -Answer $normalized -Assertion $assertion}
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
