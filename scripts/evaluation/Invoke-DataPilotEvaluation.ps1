[CmdletBinding()]
# 文件使用 UTF-8 BOM 保存，确保 Windows PowerShell 5.1 正确解析中文测试用例。
param(
    [string]$BaseUri = "http://localhost:8082",
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"

function Send-DataPilotEvaluationRequest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message,

        [string]$TaskId
    )

    $body = @{
        message = $Message
    } | ConvertTo-Json -Compress

    $headers = @{}
    if ($TaskId) {
        $headers["X-Task-Id"] = $TaskId
    }

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest `
            -UseBasicParsing `
            -Method Post `
            -Uri "$BaseUri/api/chat" `
            -Headers $headers `
            -ContentType "application/json; charset=utf-8" `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($body))

        $json = [System.Text.Encoding]::UTF8.GetString(
            $response.RawContentStream.ToArray()
        )
        $payload = $json | ConvertFrom-Json

        return [PSCustomObject]@{
            Success = $true
            StatusCode = [int]$response.StatusCode
            TaskId = [string]$response.Headers["X-Task-Id"]
            TurnId = [string]$response.Headers["X-Turn-Id"]
            ElapsedMs = $stopwatch.ElapsedMilliseconds
            Answer = $payload.answer
            Error = $null
            RawJson = $json
        }
    }
    catch {
        $errorBody = $null
        $statusCode = $null
        if ($_.Exception.Response) {
            if ($_.Exception.Response.StatusCode) {
                $statusCode = [int]$_.Exception.Response.StatusCode
            }
            $stream = $_.Exception.Response.GetResponseStream()
            if ($stream) {
                $reader = New-Object System.IO.StreamReader(
                    $stream,
                    [System.Text.Encoding]::UTF8
                )
                try {
                    $errorBody = $reader.ReadToEnd()
                }
                finally {
                    $reader.Dispose()
                }
            }
        }

        return [PSCustomObject]@{
            Success = $false
            StatusCode = $statusCode
            TaskId = $TaskId
            TurnId = $null
            ElapsedMs = $stopwatch.ElapsedMilliseconds
            Answer = $null
            Error = $_.Exception.Message
            RawJson = $errorBody
        }
    }
    finally {
        $stopwatch.Stop()
    }
}

$cases = @(
    [PSCustomObject]@{
        Name = "simple-fact"
        Description = "简单事实查询"
        Message = "当前数据库中一共有多少家店铺？"
        TaskKey = "simple-fact"
        ContinueFrom = $null
    },
    [PSCustomObject]@{
        Name = "exploratory-analysis"
        Description = "探索性数据分析"
        Message = "分析一下销量高的店铺通常有哪些特点，结论必须基于当前数据库中的真实数据。"
        TaskKey = "analysis"
        ContinueFrom = $null
    },
    [PSCustomObject]@{
        Name = "report-generation"
        Description = "复用分析结果生成报告"
        Message = "把刚才的分析整理成一份面向运营负责人的简洁 Markdown 分析报告。"
        TaskKey = "analysis"
        ContinueFrom = "analysis"
    },
    [PSCustomObject]@{
        Name = "conversation-followup"
        Description = "多轮上下文追问"
        Message = "刚才分析中最重要的三个发现是什么？只列结论和关键数字。"
        TaskKey = "analysis"
        ContinueFrom = "analysis"
    }
)

$taskIds = @{}
$results = @()

foreach ($case in $cases) {
    $continuedTaskId = $null
    if ($case.ContinueFrom) {
        $continuedTaskId = $taskIds[$case.ContinueFrom]
    }

    Write-Host ""
    Write-Host "========== $($case.Description) ==========" -ForegroundColor Cyan
    Write-Host $case.Message

    if ($case.ContinueFrom -and -not $continuedTaskId) {
        $skipReason = "前置用例 '$($case.ContinueFrom)' 未成功，无法复用同一 TaskId。"
        $result = [PSCustomObject]@{
            Name = $case.Name
            Description = $case.Description
            Message = $case.Message
            Success = $false
            Skipped = $true
            SkipReason = $skipReason
            StatusCode = $null
            TaskId = $null
            TurnId = $null
            ElapsedMs = 0
            AnswerLength = 0
            Answer = $null
            Error = $null
            RawJson = $null
        }
        $results += $result
        Write-Host "跳过  reason=$skipReason" -ForegroundColor Yellow
        continue
    }

    $response = Send-DataPilotEvaluationRequest `
        -Message $case.Message `
        -TaskId $continuedTaskId

    if ($response.Success -and $response.TaskId) {
        $taskIds[$case.TaskKey] = $response.TaskId
    }

    $answerLength = if ($response.Answer) { $response.Answer.Length } else { 0 }
    $result = [PSCustomObject]@{
        Name = $case.Name
        Description = $case.Description
        Message = $case.Message
        Success = $response.Success
        Skipped = $false
        SkipReason = $null
        StatusCode = $response.StatusCode
        TaskId = $response.TaskId
        TurnId = $response.TurnId
        ElapsedMs = $response.ElapsedMs
        AnswerLength = $answerLength
        Answer = $response.Answer
        Error = $response.Error
        RawJson = $response.RawJson
    }
    $results += $result

    if ($result.Success) {
        Write-Host "成功  elapsedMs=$($result.ElapsedMs) taskId=$($result.TaskId) turnId=$($result.TurnId)" `
            -ForegroundColor Green
        Write-Host $result.Answer
    }
    else {
        Write-Host "失败  statusCode=$($result.StatusCode) elapsedMs=$($result.ElapsedMs) error=$($result.Error)" `
            -ForegroundColor Red
        if ($result.RawJson) {
            Write-Host $result.RawJson
        }
    }
}

$passedCount = @($results | Where-Object { $_.Success }).Count
$failedCount = @($results | Where-Object { -not $_.Success -and -not $_.Skipped }).Count
$skippedCount = @($results | Where-Object { $_.Skipped }).Count

Write-Host ""
Write-Host "========== 评测汇总 ==========" -ForegroundColor Cyan
Write-Host "通过=$passedCount  失败=$failedCount  跳过=$skippedCount"

if ($OutputPath) {
    $parent = Split-Path -Parent $OutputPath
    if ($parent -and -not (Test-Path $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $results | ConvertTo-Json -Depth 8 | Set-Content -Path $OutputPath -Encoding UTF8
    Write-Host ""
    Write-Host "评测结果已写入：$OutputPath" -ForegroundColor DarkGray
}

$results
