package com.example.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 使用当前数据源查询完整受限数据集，并在本地可信环境中执行受限 Python 分析。 */
@Component
@ConditionalOnProperty(
        prefix = "datapilot.python",
        name = "enabled",
        havingValue = "true")
public class RunPythonAnalysisTool implements AgentTool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "run_python_analysis",
            "对当前数据库的一条只读 SQL 结果执行受限 Python 统计分析。适合中位数、分位数、相关系数和统计检验；简单聚合优先使用 SQL",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "sql", Map.of(
                                    "type", "string",
                                    "description", "为 Python 准备完整分析数据的一条只读 SELECT 或 WITH 查询"),
                            "code", Map.of(
                                    "type", "string",
                                    "description", "禁止 import；使用已预置的 df、pd、np、stats、Decimal，最终必须将 JSON 对象赋给 result")),
                    "required", List.of("sql", "code"),
                    "additionalProperties", false));

    private final ReadonlySqlValidator sqlValidator;
    private final ReadonlyQueryExecutor queryExecutor;
    private final PythonAnalysisRunner pythonRunner;
    private final PythonAnalysisProperties properties;
    private final ObjectMapper objectMapper;

    public RunPythonAnalysisTool(
            ReadonlySqlValidator sqlValidator,
            ReadonlyQueryExecutor queryExecutor,
            PythonAnalysisRunner pythonRunner,
            PythonAnalysisProperties properties,
            ObjectMapper objectMapper) {
        this.sqlValidator = sqlValidator;
        this.queryExecutor = queryExecutor;
        this.pythonRunner = pythonRunner;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        long toolStartedAt = System.nanoTime();
        String sql = invocation.arguments().path("sql").asText(null);
        String code = invocation.arguments().path("code").asText(null);
        if (code == null || code.isBlank()) {
            return ToolResult.failure("PYTHON_CODE_REJECTED", "Python 分析代码不能为空。");
        }
        if (code.length() > properties.getMaxCodeLength()) {
            return ToolResult.failure(
                    "PYTHON_CODE_REJECTED",
                    "Python 分析代码长度不能超过 " + properties.getMaxCodeLength() + " 个字符。");
        }
        try {
            pythonRunner.verifyAvailable();
        } catch (PythonAnalysisRunner.PythonRunnerException e) {
            return ToolResult.failure(e.errorCode(), e.getMessage());
        }

        ReadonlySqlValidator.ValidatedQuery query;
        try {
            query = sqlValidator.validate(sql);
        } catch (ReadonlySqlValidator.SqlValidationException e) {
            return ToolResult.failure(e.errorCode(), e.getMessage());
        }
        if (!query.maskedColumnIndexes().isEmpty()) {
            return ToolResult.failure(
                    "SENSITIVE_FIELD_NOT_ANALYZABLE",
                    "Python 分析不能直接使用需脱敏字段，请从 SQL 投影中移除这些字段或只查询安全的聚合结果。");
        }

        ReadonlyQueryExecutor.AnalysisDataset dataset;
        try {
            dataset = queryExecutor.executeForAnalysis(
                    query,
                    properties.getMaxInputRows(),
                    properties.getMaxInputBytes(),
                    properties.getQueryTimeoutSeconds());
        } catch (ReadonlyQueryExecutor.QueryExecutionException e) {
            return ToolResult.failure(e.errorCode(), e.getMessage());
        }

        try {
            PythonAnalysisRunner.PythonRunResult run = pythonRunner.run(dataset.json(), code);
            JsonNode root = run.root();
            ObjectNode output = objectMapper.createObjectNode();
            ObjectNode input = output.putObject("input");
            input.put("rowCount", dataset.rowCount());
            JsonNode datasetRoot = objectMapper.readTree(dataset.json());
            input.set("columns", datasetRoot.path("columns"));
            output.set("result", root.path("result"));
            JsonNode warnings = root.path("warnings");
            output.set("warnings", warnings.isArray() ? warnings : objectMapper.createArrayNode());
            ObjectNode timing = output.putObject("timing");
            timing.put("queryMs", dataset.queryMs());
            timing.put("pythonProcessMs", run.pythonProcessMs());
            timing.put("totalToolMs", elapsedMillis(toolStartedAt));
            byte[] resultBytes = objectMapper.writeValueAsBytes(output);
            if (resultBytes.length > properties.getMaxOutputBytes()) {
                return ToolResult.failure(
                        "PYTHON_OUTPUT_TOO_LARGE", "Python 工具结果超过配置的最大输出大小。");
            }
            return ToolResult.success(new String(resultBytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (PythonAnalysisRunner.PythonRunnerException e) {
            return ToolResult.failure(e.errorCode(), e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure(
                    "PYTHON_RESULT_INVALID", "Python 结果无法转换为统一 Tool Result。");
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
