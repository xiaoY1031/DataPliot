Set-StrictMode -Version 2.0

function ConvertTo-InvariantFingerprintText {
    param([AllowNull()]$Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.IFormattable]) { return $Value.ToString($null, [System.Globalization.CultureInfo]::InvariantCulture) }
    return [string]$Value
}

function ConvertTo-LengthPrefixedUtf8Hex {
    param([Parameter(Mandatory = $true)][string]$Text)
    $encoding = New-Object System.Text.UTF8Encoding($false)
    $bytes = $encoding.GetBytes($Text)
    $hex = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    return ('V{0:D10}:{1}' -f $bytes.Length, $hex)
}

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return (-join ($sha.ComputeHash($Bytes) | ForEach-Object { $_.ToString('x2') })) } finally { $sha.Dispose() }
}

function Assert-SafeSqlIdentifier {
    param([Parameter(Mandatory = $true)][string]$Value)
    if ($Value -notmatch '^[A-Za-z0-9_]+$') { throw "数据库指纹配置包含不安全的 SQL 标识符：$Value" }
}

function Invoke-MySqlFingerprintQuery {
    param(
        [string]$MySqlPath, [string]$HostName, [int]$Port, [string]$DatabaseName,
        [string]$Username, [AllowNull()][string]$Password, [AllowNull()][string]$LoginPath,
        [string]$Sql
    )
    $arguments = @('--batch', '--raw', '--skip-column-names', '--default-character-set=utf8mb4')
    if (-not [string]::IsNullOrWhiteSpace($LoginPath)) { $arguments += "--login-path=$LoginPath" }
    else { $arguments += "--host=$HostName"; $arguments += "--port=$Port"; $arguments += "--user=$Username" }
    $arguments += "--database=$DatabaseName"; $arguments += '--execute'; $arguments += $Sql
    $previousPassword = $env:MYSQL_PWD
    $tempRoot = $null
    try {
        if (-not [string]::IsNullOrEmpty($Password)) { $env:MYSQL_PWD = $Password }
        elseif (-not [string]::IsNullOrWhiteSpace($LoginPath)) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
        $tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('datapilot-fingerprint-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
        $stdoutPath = Join-Path $tempRoot 'stdout.txt'; $stderrPath = Join-Path $tempRoot 'stderr.txt'
        $quotedArguments = @($arguments | ForEach-Object { '"' + ([string]$_).Replace('"', '\"') + '"' })
        $process = Start-Process -FilePath $MySqlPath -ArgumentList ($quotedArguments -join ' ') -NoNewWindow -Wait -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
        $stdout = if (Test-Path $stdoutPath) { [System.IO.File]::ReadAllLines($stdoutPath, [System.Text.Encoding]::UTF8) } else { @() }
        $stderr = if (Test-Path $stderrPath) { [System.IO.File]::ReadAllText($stderrPath, [System.Text.Encoding]::UTF8) } else { '' }
        if ($process.ExitCode -ne 0) {
            $safeError = $stderr -replace '(?i)(password|pwd)\s*[=:]\s*\S+', '$1=***'
            throw "数据库指纹查询失败：$safeError"
        }
        return @($stdout | ForEach-Object { [string]$_ })
    }
    finally {
        if (-not [string]::IsNullOrWhiteSpace($tempRoot) -and (Test-Path -LiteralPath $tempRoot)) { Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue }
        if ($null -eq $previousPassword) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue } else { $env:MYSQL_PWD = $previousPassword }
    }
}

function Get-DataPilotDatabaseFingerprint {
    param(
        [Parameter(Mandatory = $true)]$DatabaseConfig,
        [string]$HostName, [int]$Port, [string]$DatabaseName, [string]$Username,
        [AllowNull()][string]$Password, [AllowNull()][string]$LoginPath, [AllowNull()][string]$MySqlPath
    )
    if ([string]::IsNullOrWhiteSpace($MySqlPath)) {
        $command = Get-Command mysql -ErrorAction SilentlyContinue
        if ($null -eq $command) { throw '未找到 mysql 客户端。请安装 MySQL CLI 或通过 -MySqlPath 指定路径。' }
        $MySqlPath = $command.Source
    }
    if ([string]::IsNullOrWhiteSpace($HostName)) { $HostName = [string]$DatabaseConfig.host }
    if ($Port -le 0) { $Port = [int]$DatabaseConfig.port }
    if ([string]::IsNullOrWhiteSpace($DatabaseName)) { $DatabaseName = [string]$DatabaseConfig.name }
    if ([string]::IsNullOrWhiteSpace($Username) -and [string]::IsNullOrWhiteSpace($LoginPath)) { throw '数据库指纹需要 DATAPILOT_DB_USERNAME、-DatabaseUsername 或 -MySqlLoginPath。' }

    $encoding = New-Object System.Text.UTF8Encoding($false)
    $tableResults = @()
    foreach ($tableConfig in @($DatabaseConfig.fingerprint.tables)) {
        $tableName = [string]$tableConfig.table; Assert-SafeSqlIdentifier -Value $tableName
        $fields = @($tableConfig.fields | ForEach-Object { [string]$_ }); $primaryKey = @($tableConfig.primaryKey | ForEach-Object { [string]$_ })
        foreach ($field in ($fields + $primaryKey)) { Assert-SafeSqlIdentifier -Value $field }
        $fieldSql = ($fields | ForEach-Object { [char]96 + $_ + [char]96 }) -join ', '
        $orderSql = ($primaryKey | ForEach-Object { [char]96 + $_ + [char]96 + ' ASC' }) -join ', '
        $quotedTable = [char]96 + $tableName + [char]96
        $sql = "SELECT JSON_ARRAY($fieldSql) FROM $quotedTable ORDER BY $orderSql"
        $rows = Invoke-MySqlFingerprintQuery -MySqlPath $MySqlPath -HostName $HostName -Port $Port -DatabaseName $DatabaseName -Username $Username -Password $Password -LoginPath $LoginPath -Sql $sql
        $canonicalRows = @()
        foreach ($rowJson in $rows) {
            if ([string]::IsNullOrWhiteSpace($rowJson)) { continue }
            $parsedRow = $rowJson | ConvertFrom-Json
            $values = @($parsedRow)
            if ($values.Count -ne $fields.Count) { throw "表 $tableName 的指纹查询列数异常：期望 $($fields.Count)，实际 $($values.Count)。" }
            $encodedFields = @()
            for ($i = 0; $i -lt $fields.Count; $i++) {
                $valueText = ConvertTo-InvariantFingerprintText -Value $values[$i]
                $encodedValue = if ($null -eq $valueText) { 'N' } else { ConvertTo-LengthPrefixedUtf8Hex -Text $valueText }
                $encodedFields += ($fields[$i] + '=' + $encodedValue)
            }
            $canonicalRows += ($encodedFields -join '|')
        }
        $header = 'TABLE=' + (ConvertTo-LengthPrefixedUtf8Hex -Text $tableName)
        $canonical = if ($canonicalRows.Count -gt 0) { $header + [char]10 + ($canonicalRows -join [char]10) } else { $header }
        $hash = Get-Sha256Hex -Bytes $encoding.GetBytes($canonical)
        $expected = [string]$tableConfig.expectedSha256
        $matches = -not [string]::IsNullOrWhiteSpace($expected) -and $expected -ne 'PENDING_RECOMPUTE' -and $hash -eq $expected.ToLowerInvariant()
        $tableResults += [PSCustomObject]@{ table=$tableName; fields=$fields; primaryKey=$primaryKey; rowCount=$canonicalRows.Count; expectedSha256=$expected; actualSha256=$hash; matches=$matches }
    }
    $baselineReady = @($tableResults | Where-Object { $_.expectedSha256 -eq 'PENDING_RECOMPUTE' -or [string]::IsNullOrWhiteSpace($_.expectedSha256) }).Count -eq 0
    $allMatch = $baselineReady -and @($tableResults | Where-Object { -not $_.matches }).Count -eq 0
    $databaseIdentifier = 'mysql://{0}:{1}/{2}' -f $HostName, $Port, $DatabaseName
    return [PSCustomObject]@{ algorithm=[string]$DatabaseConfig.fingerprint.algorithm; databaseIdentifier=$databaseIdentifier; baselineReady=$baselineReady; allMatch=$allMatch; tables=$tableResults }
}
