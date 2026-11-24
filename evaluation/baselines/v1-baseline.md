# Evaluation V1 Benchmark Baseline

## 结论

本次有效基线运行完整执行了现有 V1 的全部 3 个 case 和 17 条 assertion。数据库 fingerprint 在调用 LLM 前通过，3 个 Agent 请求均返回 HTTP 200，评测结果可比较。

- 运行状态：`COMPLETED_WITH_FAILURES`
- Case 通过率：`2 / 3 = 66.67%`
- Assertion 通过率：`16 / 17 = 94.12%`
- 失败 case：`shop-category-distribution`
- 失败 assertion：`category-count-ranks`
- 环境或数据是否使有效运行不完整：否
- 数据问题：未发现；fingerprint 全部匹配

不要将 94.12% 的 assertion 通过率解释为 case 通过率；本次 case 通过率是 66.67%。

## 运行标识

| 项目 | 值 |
|---|---|
| Evaluation run ID | `d0928edf-1ba5-4180-82f2-92f890b51bc5` |
| 开始时间 | `2026-11-24T19:08:16.3708118+08:00` |
| 完成时间 | `2026-11-24T19:08:46.9181314+08:00` |
| 总耗时 | 约 30.55 秒 |
| Git commit | `f5dc1aac9ac0b40a9d97c7df3fc0ef5b2856f1f1` |
| Git commit 读取状态 | `AVAILABLE` |
| 工作区状态 | 非 clean；已有 `application.yml` 端口改动及若干未跟踪文件。本次没有修改业务逻辑、用例、Gold 或数据库数据 |
| Cases 文件 | `evaluation/cases.json` |
| Cases SHA-256 | `c47475e70951498a558befd809fae3a7e6937e7f8bc7f77f94b136653d9dd43c` |
| System prompt SHA-256 | `c92c0bb5a05e070dd02ae9bd5d3f1d55431f169eb522aece5f56e3c526018c20` |
| Data analysis skill SHA-256 | `038099374303937412c25a899137747a6fb4ed7676ac94405f0ad0676d5c5aeb` |

## 实际命令

服务使用 IntelliJ `HarnessApplication` 运行配置中的环境变量，但报告和日志不记录 API Key 或数据库密码。为避免占用现有 `8082` 服务，本次临时覆盖端口为 `8084`。

```powershell
$env:SERVER_PORT = '8084'
mvn '-Dmaven.resources.skip=true' spring-boot:run
```

评测命令：

```powershell
& .\scripts\evaluation\Invoke-DataPilotEvaluationV1.ps1 `
  -BaseUri 'http://localhost:8084' `
  -OutputPath '.\evaluation\baselines\v1-baseline-run.json'
```

评测完成后停止了本次临时服务。服务日志末尾的 Maven `BUILD FAILURE` 是进程被主动终止造成的，不代表评测失败；评测 JSON 已在终止前完整写入。

## 模型与配置

| 项目 | 实际值或证据 |
|---|---|
| 请求模型配置 | `deepseek-flash` |
| API host | `api.deepseek.com` |
| 实际响应模型 | `deepseek-flash` |
| 实际响应证据 | 服务日志中的全部 17 次“大模型调用完成”均记录 `model=deepseek-flash` |
| 服务 | Spring Boot `3.5.16`，Java `17` |
| 评测 Base URI | `http://localhost:8084` |
| 数据库 | MySQL `localhost:3306/hmdp` |

模型结论以服务启动日志和实际 LLM 响应日志为准，而不是只依据 IDE 配置。日志未记录 API Key 或数据库密码。

## Database Fingerprint

Fingerprint 算法为 `datapilot-canonical-row-v1`。校验在任何 case 的 LLM 调用之前执行；本次 `baselineReady=true`、`allMatch=true`，运行状态为 `COMPARABLE`。

| 表 | 行数 | Expected SHA-256 | Actual SHA-256 | 结果 |
|---|---:|---|---|---|
| `tb_shop` | 30 | `e2e51c1916efc4903b7b890dc1eec997311d90af8c582b494a317359bcaa887b` | `e2e51c1916efc4903b7b890dc1eec997311d90af8c582b494a317359bcaa887b` | PASS |
| `tb_shop_type` | 10 | `54870e3f9db333a9964dc51e7401a623bee4abe7ae777652cb1852ff8e3bd03d` | `54870e3f9db333a9964dc51e7401a623bee4abe7ae777652cb1852ff8e3bd03d` | PASS |

未绕过 fingerprint，没有修改 Gold，也没有修改数据库数据。

## Case 结果

| Case | HTTP | 耗时 | Assertion | Case 结果 |
|---|---:|---:|---:|---|
| `shop-count` | 200 | 5,635 ms | 1 / 1 PASS | PASS |
| `shop-category-distribution` | 200 | 9,732 ms | 9 / 10 PASS | FAIL |
| `estimated-sales-top5` | 200 | 14,691 ms | 6 / 6 PASS | PASS |

Case 汇总：2 个通过，1 个失败，0 个需人工复核，case 通过率 66.67%。

## Assertion 结果

| Case | Assertion | 结果 | 实际检查结果或失败原因 |
|---|---|---|---|
| `shop-count` | `shop-count-value` | PASS | 在店铺语境中明确给出 30 家 |
| `shop-category-distribution` | `category-total` | PASS | 总店铺数 30 |
| `shop-category-distribution` | `food-count-share` | PASS | 美食 9 家、30.00% |
| `shop-category-distribution` | `ktv-count-share` | PASS | KTV 5 家、16.67% |
| `shop-category-distribution` | `remaining-category-counts` | PASS | 其余八类均为 2 家 |
| `shop-category-distribution` | `category-count-ranks` | **FAIL** | 模型把八个数量同为 2 的分类依次写成第 3 至第 10；Gold 要求竞赛排名全部并列第 3。失败分类：健身运动、按摩·足疗、美容SPA、亲子游乐、酒吧、轰趴馆、美睫·美甲 |
| `shop-category-distribution` | `category-count-sum` | PASS | 十类数量合计 30 |
| `shop-category-distribution` | `remaining-categories-not-different` | PASS | 未声称后八类数量各不相同 |
| `shop-category-distribution` | `top2-order` | PASS | 美食、KTV 顺序正确 |
| `shop-category-distribution` | `top2-count` | PASS | Top 2 合计 14 家 |
| `shop-category-distribution` | `top2-share` | PASS | Top 2 占比 46.67% |
| `estimated-sales-top5` | `top5-order-and-amounts` | PASS | Top 5 顺序和各店估算金额均匹配 |
| `estimated-sales-top5` | `total-estimated-amount` | PASS | 全部店铺合计 19,777,107 元 |
| `estimated-sales-top5` | `top5-estimated-amount` | PASS | Top 5 合计 10,761,338 元 |
| `estimated-sales-top5` | `top5-estimated-share` | PASS | Top 5 占比 54.41% |
| `estimated-sales-top5` | `estimated-nature-present` | PASS | 明确保留“估算”性质 |
| `estimated-sales-top5` | `false-real-revenue-absent` | PASS | 未将估算金额描述为真实营收或精确销售额 |

Assertion 汇总：16 条通过，1 条失败，assertion 通过率 94.12%。

## 失败详情

唯一正式失败发生在 Agent 输出质量层，并由 assertion 校验层正确检出：

- Agent 已正确给出十个分类的数量、占比、Top 2 和总和。
- 对八个数量同为 2 的分类，回答表格使用了连续序号 `3, 4, 5, 6, 7, 8, 9, 10` 作为排名。
- V1 Gold 采用竞赛排名，要求这八类全部为第 3。
- 因 `category-count-ranks` 失败，`shop-category-distribution` 整个 case 判定失败。

这不是 fingerprint、数据库数据、HTTP、模型 API 或 assertion 引擎运行异常。

## 完整性与运行问题

有效基线运行本身完整：fingerprint 通过，全部 3 个 case 均收到 HTTP 200，17 条 assertion 均执行并产生明确状态和失败原因。

正式运行前遇到过以下环境问题，均未计入本基线结果：

1. Windows PowerShell 5.1 会把 fingerprint 查询返回的 JSON 数组错误展开，产生“期望 7 列、实际 1 列”的兼容性误报；有效运行改用 PowerShell 7，未修改 fingerprint 实现或 Gold。
2. 沙箱内启动的服务不能访问 `api.deepseek.com`，LLM 请求被网络策略拒绝；有效运行经授权在非沙箱网络环境执行。
3. Maven 在沙箱内无法覆盖 `target/classes/application.yml`。有效运行使用 `-Dmaven.resources.skip=true`；运行前已验证源文件和 `target/classes` 中的 `application.yml` SHA-256 完全相同，因此未引入配置偏差。
4. `estimated-sales-top5` 执行过程中有一次 SQL 因只读安全规则被拒绝，Agent 随后重试成功；该 case HTTP 200 且 6 条 assertion 全部通过。

本次没有为通过测试而修改用例、Gold、数据库、Agent 业务逻辑或 assertion 标准。

## 保存产物

| 文件 | 用途 | SHA-256 |
|---|---|---|
| `evaluation/baselines/v1-baseline-run.json` | 原始评测结果、回答、HTTP 元数据、逐 assertion 结果和失败原因 | `599a1eeefead58eb055480805acf7a9c7ddf42f6058eb8c253d6e5b76d8b7b24` |
| `evaluation/baselines/v1-baseline-service.log` | 服务启动、实际 LLM 响应模型、工具调用和运行日志 | `74877f28ef8f136eead18f0a53787f595ffc55824bb6feabf2b25c358a899021` |
| `evaluation/baselines/v1-baseline-service.err.log` | 服务标准错误；本次为空 | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |

