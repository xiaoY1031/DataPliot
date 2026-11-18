package com.example.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunPythonAnalysisToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReadonlySqlValidator validator = mock(ReadonlySqlValidator.class);
    private final ReadonlyQueryExecutor queryExecutor = mock(ReadonlyQueryExecutor.class);
    private final PythonAnalysisRunner runner = mock(PythonAnalysisRunner.class);
    private final PythonAnalysisProperties properties = new PythonAnalysisProperties();
    private final RunPythonAnalysisTool tool = new RunPythonAnalysisTool(
            validator, queryExecutor, runner, properties, objectMapper);
    private final ToolExecutor executor = new ToolExecutor(
            new ToolRegistry(List.of(tool)), objectMapper);

    @Test
    void definesSqlAndCodeParameters() {
        ToolDefinition definition = tool.definition();

        assertEquals("run_python_analysis", definition.name());
        assertEquals("object", definition.parameters().get("type"));
        assertEquals(List.of("sql", "code"), definition.parameters().get("required"));
        assertEquals(false, definition.parameters().get("additionalProperties"));
        Map<?, ?> parameters = (Map<?, ?>) definition.parameters().get("properties");
        assertEquals("string", ((Map<?, ?>) parameters.get("sql")).get("type"));
        assertEquals("string", ((Map<?, ?>) parameters.get("code")).get("type"));
    }

    @Test
    void returnsStructuredAnalysisResultAndSeparateTimings() throws Exception {
        ReadonlySqlValidator.ValidatedQuery query = new ReadonlySqlValidator.ValidatedQuery(
                "SELECT amount FROM orders", java.util.Set.of("orders"), java.util.Set.of());
        byte[] datasetJson = ("{\"columns\":[{\"name\":\"amount\","
                + "\"type\":\"DECIMAL\",\"jdbcType\":3,"
                + "\"encoding\":\"DECIMAL_STRING\"}],"
                + "\"rows\":[{\"amount\":\"12.50\"}],\"rowCount\":1}")
                .getBytes(StandardCharsets.UTF_8);
        when(validator.validate(anyString())).thenReturn(query);
        when(queryExecutor.executeForAnalysis(any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new ReadonlyQueryExecutor.AnalysisDataset(datasetJson, 1, 7));
        JsonNode pythonRoot = objectMapper.readTree(
                "{\"result\":{\"median\":\"12.50\"},\"warnings\":[]}");
        when(runner.run(any(), anyString())).thenReturn(
                new PythonAnalysisRunner.PythonRunResult(pythonRoot, 11));

        ToolResult result = execute(
                "SELECT amount FROM orders",
                "result = {'median': str(df['amount'].median())}");

        assertTrue(result.success(), result.toString());
        JsonNode content = objectMapper.readTree(result.content());
        assertEquals(1, content.path("input").path("rowCount").asInt());
        assertEquals("DECIMAL_STRING",
                content.path("input").path("columns").get(0).path("encoding").asText());
        assertEquals("12.50", content.path("result").path("median").asText());
        assertEquals(7, content.path("timing").path("queryMs").asLong());
        assertEquals(11, content.path("timing").path("pythonProcessMs").asLong());
        assertTrue(content.path("timing").path("totalToolMs").asLong() >= 0);
    }

    @Test
    void returnsConfigurationErrorBeforeSqlOrDatabaseWork() throws Exception {
        PythonAnalysisRunner.PythonRunnerException unavailable =
                new PythonAnalysisRunner.PythonRunnerException(
                        "PYTHON_NOT_CONFIGURED",
                        "已启用 Python 分析，但未配置解释器路径。");
        org.mockito.Mockito.doThrow(unavailable).when(runner).verifyAvailable();

        ToolResult result = execute("SELECT 1", "result = {}");

        assertFalse(result.success());
        assertEquals("PYTHON_NOT_CONFIGURED", result.errorCode());
        verify(validator, never()).validate(anyString());
        verify(queryExecutor, never()).executeForAnalysis(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void propagatesDatasetLimitAndPythonErrors() throws Exception {
        ReadonlySqlValidator.ValidatedQuery query = new ReadonlySqlValidator.ValidatedQuery(
                "SELECT * FROM orders", java.util.Set.of("orders"), java.util.Set.of());
        when(validator.validate(anyString())).thenReturn(query);
        when(queryExecutor.executeForAnalysis(any(), anyInt(), anyInt(), anyInt()))
                .thenThrow(new ReadonlyQueryExecutor.QueryExecutionException(
                        "DATASET_LIMIT_EXCEEDED", "数据集过大"));

        ToolResult datasetFailure = execute("SELECT * FROM orders", "result = {}");

        assertEquals("DATASET_LIMIT_EXCEEDED", datasetFailure.errorCode());
        verify(runner, never()).run(any(), anyString());
    }

    @Test
    void rejectsDirectMaskedColumnsBeforeQueryExecution() throws Exception {
        ReadonlySqlValidator.ValidatedQuery query = new ReadonlySqlValidator.ValidatedQuery(
                "SELECT phone FROM users",
                java.util.Set.of("users"),
                java.util.Set.of(1));
        when(validator.validate(anyString())).thenReturn(query);

        ToolResult result = execute("SELECT phone FROM users", "result = {}");

        assertEquals("SENSITIVE_FIELD_NOT_ANALYZABLE", result.errorCode());
        verify(queryExecutor, never()).executeForAnalysis(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void validatesArgumentsThroughExistingToolExecutor() {
        ToolResult missing = executor.execute(
                "call-1", "run_python_analysis", "{\"sql\":\"SELECT 1\"}");
        ToolResult extra = executor.execute(
                "call-2",
                "run_python_analysis",
                "{\"sql\":\"SELECT 1\",\"code\":\"result = {}\",\"path\":\"x\"}");

        assertEquals("INVALID_ARGUMENTS", missing.errorCode());
        assertEquals("INVALID_ARGUMENTS", extra.errorCode());
    }

    private ToolResult execute(String sql, String code) {
        String arguments = objectMapper.createObjectNode()
                .put("sql", sql)
                .put("code", code)
                .toString();
        return executor.execute("call-python", "run_python_analysis", arguments);
    }
}
