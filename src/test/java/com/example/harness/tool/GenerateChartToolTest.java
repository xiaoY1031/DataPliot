package com.example.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateChartToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReadonlySqlValidator sqlValidator = mock(ReadonlySqlValidator.class);
    private final ReadonlyQueryExecutor queryExecutor = mock(ReadonlyQueryExecutor.class);
    private final ChartDataValidator dataValidator = mock(ChartDataValidator.class);
    private final ChartGenerationRunner chartRunner = mock(ChartGenerationRunner.class);
    private final ChartArtifactStore artifactStore = mock(ChartArtifactStore.class);
    private final ChartGenerationProperties properties = new ChartGenerationProperties();
    private final GenerateChartTool tool = new GenerateChartTool(
            sqlValidator, queryExecutor, dataValidator, chartRunner, artifactStore,
            properties, objectMapper);
    private final ToolExecutor executor = new ToolExecutor(
            new ToolRegistry(List.of(tool)), objectMapper);

    @Test
    void definesStableChartContractAndSeriesMeaning() {
        ToolDefinition definition = tool.definition();

        assertEquals("generate_chart", definition.name());
        assertEquals(List.of("sql", "chart_type", "title"),
                definition.parameters().get("required"));
        assertEquals(false, definition.parameters().get("additionalProperties"));
        assertTrue(definition.description().contains("用户明确要求图表"));
        assertTrue(definition.description().contains("时间趋势、多个类别比较、总体分布或数值关系"));
        assertTrue(definition.description().contains("单个数字、简单列表"));
        Map<?, ?> fields = (Map<?, ?>) definition.parameters().get("properties");
        assertTrue(((String) ((Map<?, ?>) fields.get("series")).get("description"))
                .contains("类别分组维度"));
    }

    @Test
    void returnsArtifactMetadataAndSeparateTimings() throws Exception {
        ReadonlySqlValidator.ValidatedQuery query = query(Set.of());
        byte[] dataset = dataset();
        ChartDataValidator.ValidatedChart validated = new ChartDataValidator.ValidatedChart(
                new ChartDataValidator.ChartSpec(
                        "horizontal_bar", "各类店铺数量", "type_name",
                        "shop_count", "", "店铺类型", "店铺数（家）", 20),
                2, 0);
        when(sqlValidator.validate(any())).thenReturn(query);
        when(queryExecutor.executeForAnalysis(any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new ReadonlyQueryExecutor.AnalysisDataset(dataset, 2, 8));
        when(dataValidator.validate(any(), any())).thenReturn(validated);
        when(chartRunner.run(any(), any())).thenReturn(
                new ChartGenerationRunner.ChartRunResult(
                        new byte[]{1, 2, 3}, 1200, 720, List.of(), 19));
        Instant now = Instant.now();
        when(artifactStore.save(any(), anyInt(), anyInt())).thenReturn(
                new ChartArtifactStore.ChartArtifact(
                        "123e4567-e89b-12d3-a456-426614174000",
                        Path.of("chart.png"), 1200, 720, 3, now, now.plusSeconds(3600)));

        ToolResult result = execute(
                "SELECT type_name, COUNT(*) shop_count FROM shops GROUP BY type_name",
                "horizontal_bar", "各类店铺数量", "type_name", "shop_count");

        assertTrue(result.success(), result.toString());
        JsonNode content = objectMapper.readTree(result.content());
        assertEquals("/api/artifacts/charts/123e4567-e89b-12d3-a456-426614174000",
                content.path("artifact").path("url").asText());
        assertTrue(content.path("artifact").path("markdown").asText()
                .startsWith("![各类店铺数量]"));
        assertEquals(2, content.path("data").path("rowCount").asInt());
        assertTrue(content.path("data").path("complete").asBoolean());
        assertEquals(8, content.path("timing").path("queryMs").asLong());
        assertEquals(19, content.path("timing").path("renderMs").asLong());
    }

    @Test
    void refusesSensitiveProjectionBeforeDatabaseExecution() throws Exception {
        when(sqlValidator.validate(any())).thenReturn(query(Set.of(0)));

        ToolResult result = execute(
                "SELECT phone FROM users", "bar", "手机号", "phone", "phone");

        assertFalse(result.success());
        assertEquals("SENSITIVE_FIELD_NOT_CHARTABLE", result.errorCode());
        verify(queryExecutor, never()).executeForAnalysis(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void propagatesSqlAndChartValidationFailures() throws Exception {
        when(sqlValidator.validate(any())).thenThrow(rejectedSql());
        assertEquals("SQL_SAFETY_REJECTED", execute(
                "DELETE FROM shops", "bar", "x", "x", "y").errorCode());

        reset(sqlValidator);
        when(sqlValidator.validate(any())).thenReturn(query(Set.of()));
        when(queryExecutor.executeForAnalysis(any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new ReadonlyQueryExecutor.AnalysisDataset(dataset(), 2, 1));
        when(dataValidator.validate(any(), any())).thenThrow(
                new ChartDataValidator.ChartValidationException(
                        "CHART_DATA_NOT_AGGREGATED", "请先聚合"));

        ToolResult duplicate = execute(
                "SELECT month, region, sales FROM monthly_sales",
                "line", "销售趋势", "month", "sales");

        assertEquals("CHART_DATA_NOT_AGGREGATED", duplicate.errorCode());
        verify(chartRunner, never()).run(any(), any());
    }

    @Test
    void validatesArgumentsThroughExistingToolExecutor() {
        ToolResult missing = executor.execute(
                "call-1", "generate_chart", "{\"sql\":\"SELECT 1\"}");
        ToolResult extra = executor.execute(
                "call-2", "generate_chart",
                "{\"sql\":\"SELECT 1\",\"chart_type\":\"bar\","
                        + "\"title\":\"t\",\"unknown\":true}");

        assertEquals("INVALID_ARGUMENTS", missing.errorCode());
        assertEquals("INVALID_ARGUMENTS", extra.errorCode());
    }

    private ReadonlySqlValidator.ValidatedQuery query(Set<Integer> maskedColumns) {
        return new ReadonlySqlValidator.ValidatedQuery(
                "SELECT 1", Set.of("shops"), maskedColumns);
    }

    private ReadonlySqlValidator.SqlValidationException rejectedSql() {
        try {
            new ReadonlySqlValidator(SensitiveFieldPolicy.none())
                    .validate("DELETE FROM shops");
            throw new AssertionError("expected SQL rejection");
        } catch (ReadonlySqlValidator.SqlValidationException e) {
            return e;
        }
    }

    private byte[] dataset() {
        return ("{\"columns\":["
                + "{\"name\":\"type_name\",\"type\":\"VARCHAR\",\"jdbcType\":12},"
                + "{\"name\":\"shop_count\",\"type\":\"BIGINT\",\"jdbcType\":-5}],"
                + "\"rows\":[{\"type_name\":\"美食\",\"shop_count\":9},"
                + "{\"type_name\":\"KTV\",\"shop_count\":5}],\"rowCount\":2}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private ToolResult execute(
            String sql, String type, String title, String x, String y) {
        String arguments = objectMapper.createObjectNode()
                .put("sql", sql)
                .put("chart_type", type)
                .put("title", title)
                .put("x", x)
                .put("y", y)
                .toString();
        return executor.execute("call-chart", "generate_chart", arguments);
    }
}
