# Evaluation V1 Benchmark Baseline After Ranking Fix

## 结论

本次修复后基线完整执行了 Evaluation V1 的全部 3 个 case 和 17 条 assertion。数据库 fingerprint 在调用 LLM 前通过，3 个 Agent 请求均返回 HTTP 200，结果可与原始基线比较。

- 运行状态：`COMPLETED`
- 可比性状态：`COMPARABLE`
- Case 通过率：`3 / 3 = 100.00%`
- Assertion 通过率：`17 / 17 = 100.00%`
- 失败 case：无
- 失败 assertion：无
- 环境或数据问题：未发现；本次运行完整，数据库 fingerprint 全部匹配

Case 通过率和 assertion 通过率分别计算，本次二者均为 100.00%。

## 归档来源

| 文件 | 路径 | SHA-256 |
|---|---|---|
| 原始基线报告 | `evaluation/baselines/v1-baseline.md` | `261fd14fd7d8bb1471e69d705b960030f45bd7d39ca9fee9d3c72c82aa9d57c2` |
| 原始基线结果 | `evaluation/baselines/v1-baseline-run.json` | `599a1eeefead58eb055480805acf7a9c7ddf42f6058eb8c253d6e5b76d8b7b24` |
| 原始基线服务日志 | `evaluation/baselines/v1-baseline-service.log` | `74877f28ef8f136eead18f0a53787f595ffc55824bb6feabf2b25c358a899021` |
| 修复后完整运行结果 | `evaluation-results/v1-ranking-fix-full.json` | `f29d8ed469a4403b281e2e7aaf7ac12f346464f1b16dde2e109f548687d78f7f` |
| 修复后服务日志 | `evaluation-results/v1-ranking-fix-service.log` | `9fb1c223ad8f08252c23df681d3d14955d5daf516a94915c54389822ac16df3d` |

本报告新建为独立归档，没有覆盖或删除上述任何历史文件。

## 运行标识

| 项目 | 值 |
|---|---|
| Evaluation run ID | `d753c5c6-1492-41d7-befc-f57090c138ae` |
| 开始时间 | `2026-11-24T19:44:30.0096991+08:00` |
| 完成时间 | `2026-11-24T19:44:56.2543648+08:00` |
| 总耗时 | 约 26.24 秒 |
| Git commit | `f5dc1aac9ac0b40a9d97c7df3fc0ef5b2856f1f1` |
| Git commit 读取状态 | `AVAILABLE` |
| System prompt SHA-256 | `5e359dec18ca1820513bea9266a47f6d2a8885a057f188fe7a3c49e934441dfe` |
| Data analysis skill SHA-256 | `038099374303937412c25a899137747a6fb4ed7676ac94405f0ad0676d5c5aeb` |

Git commit 与原始基线相同。排名修复来自该 commit 之上的工作区 System Prompt 修改，因此不能将该 commit 解释为已经包含修复。原始基线的 System Prompt SHA-256 为 `c92c0bb5a05e070dd02ae9bd5d3f1d55431f169eb522aece5f56e3c526018c20`；Data Analysis Skill 哈希在修复前后保持不变。

## 实际模型与运行配置

| 项目 | 实际值或证据 |
|---|---|
| 请求模型配置 | `deepseek-flash` |
| API host | `api.deepseek.com` |
| 实际响应模型 | `deepseek-flash` |
| 实际响应证据 | 完整 V1 运行期间的 15 次“大模型调用完成”日志均记录 `model=deepseek-flash` |
| 服务运行时 | Java 17、Tomcat 10.1.55 |
| 服务端口 | `8084` |
| Evaluation Base URI | `http://localhost:8084` |
| 数据库 | `mysql://localhost:3306/hmdp` |
| Cases 文件 | `evaluation/cases.json` |

模型和 API host 以修复后服务启动日志及实际 LLM 完成日志为准；Base URI、数据库标识和模型配置同时记录在修复后运行结果中。报告未记录 API Key、数据库密码或其他敏感值。

## Database Fingerprint

Fingerprint 算法为 `datapilot-canonical-row-v1`。修复后运行记录为 `baselineReady=true`、`allMatch=true`，在 LLM case 执行前通过校验。

| 表 | 行数 | Expected SHA-256 | Actual SHA-256 | 结果 |
|---|---:|---|---|---|
| `tb_shop` | 30 | `e2e51c1916efc4903b7b890dc1eec997311d90af8c582b494a317359bcaa887b` | `e2e51c1916efc4903b7b890dc1eec997311d90af8c582b494a317359bcaa887b` | PASS |
| `tb_shop_type` | 10 | `54870e3f9db333a9964dc51e7401a623bee4abe7ae777652cb1852ff8e3bd03d` | `54870e3f9db333a9964dc51e7401a623bee4abe7ae777652cb1852ff8e3bd03d` | PASS |

未绕过 fingerprint，没有修改 Gold、`cases.json` 或数据库数据。

## Case 结果

| Case | HTTP | 耗时 | Assertion | 结果 |
|---|---:|---:|---:|---|
| `shop-count` | 200 | 4,986 ms | 1 / 1 PASS | PASS |
| `shop-category-distribution` | 200 | 8,969 ms | 10 / 10 PASS | PASS |
| `estimated-sales-top5` | 200 | 11,795 ms | 6 / 6 PASS | PASS |

Case 汇总：3 个通过，0 个失败，0 个需人工复核，case 通过率 100.00%。

## Assertion 结果

| Case | Assertion | 结果 | 检查内容 |
|---|---|---|---|
| `shop-count` | `shop-count-value` | PASS | 回答明确表达店铺数量为 30，而不是仅出现无关数字 30。 |
| `shop-category-distribution` | `category-total` | PASS | 总店铺数为 30。 |
| `shop-category-distribution` | `food-count-share` | PASS | 美食为 9 家，占 30.00%。 |
| `shop-category-distribution` | `ktv-count-share` | PASS | KTV 为 5 家，占 16.67%。 |
| `shop-category-distribution` | `remaining-category-counts` | PASS | 其余八个分类的店铺数均为 2。 |
| `shop-category-distribution` | `category-count-ranks` | PASS | 分类名称、店铺数量和竞赛排名正确；其余八类全部并列第 3。 |
| `shop-category-distribution` | `category-count-sum` | PASS | 十个分类的店铺数量之和为 30。 |
| `shop-category-distribution` | `remaining-categories-not-different` | PASS | 回答未将后八类描述为店铺数量各不相同。 |
| `shop-category-distribution` | `top2-order` | PASS | 数量最多的两个分类依次为美食、KTV。 |
| `shop-category-distribution` | `top2-count` | PASS | Top 2 合计 14 家。 |
| `shop-category-distribution` | `top2-share` | PASS | Top 2 合计占 46.67%。 |
| `estimated-sales-top5` | `top5-order-and-amounts` | PASS | Top 5 店铺顺序和估算金额正确。 |
| `estimated-sales-top5` | `total-estimated-amount` | PASS | 全部店铺估算金额合计为 19,777,107 元。 |
| `estimated-sales-top5` | `top5-estimated-amount` | PASS | Top 5 估算金额合计为 10,761,338 元。 |
| `estimated-sales-top5` | `top5-estimated-share` | PASS | Top 5 估算金额占比为 54.41%。 |
| `estimated-sales-top5` | `estimated-nature-present` | PASS | 回答明确保留估算性质。 |
| `estimated-sales-top5` | `false-real-revenue-absent` | PASS | 回答未把估算金额描述为真实营收或精确销售额。 |

Assertion 汇总：17 条自动 assertion 全部通过，assertion 通过率 100.00%，无失败原因需要记录。

## 修复前后对比

| 指标 | 原始基线 | 修复后基线 | 变化 |
|---|---:|---:|---:|
| 通过 case | 2 / 3 | 3 / 3 | +1 |
| Case 通过率 | 66.67% | 100.00% | +33.33 个百分点 |
| 通过 assertion | 16 / 17 | 17 / 17 | +1 |
| Assertion 通过率 | 94.12% | 100.00% | +5.88 个百分点 |
| `shop-category-distribution` | FAIL（9 / 10 assertions） | PASS（10 / 10 assertions） | `category-count-ranks` 由 FAIL 变为 PASS |

原始基线唯一失败为 `shop-category-distribution / category-count-ranks`。其余两个 case 及其 assertions 在修复前后均通过。

## 修复说明

修复在最终答案阶段的通用 System Prompt 自检规则中明确了排名语义：相同指标值必须使用相同排名；“排名”默认采用竞赛排名，即 SQL `RANK()` 语义，例如 `9、5、2、2、1` 对应 `1、2、3、3、5`；如果只是展示排序后的行位置，应使用“序号”而不是“排名”。

该规则没有针对当前八个分类硬编码，也没有修改 Agent 执行逻辑、Gold、`cases.json`、数据库或 Data Analysis Skill。修复后回答将八个数量均为 2 的分类全部列为第 3，`category-count-ranks` assertion 通过；完整 V1 的其他 16 条 assertion 也继续通过。

## 完整性结论

本次修复后运行完整且可比较：fingerprint 匹配、所有 HTTP 请求成功、全部 case 和 assertion 均有结果、实际模型可从服务日志核实。未发现环境、数据库或数据问题，也没有为通过测试修改评测标准或历史基线。
