package com.example.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 使用完整只读查询结果和固定 Python 协议生成 PNG 图表 artifact。 */
@Component
@ConditionalOnProperty(prefix = "datapilot.chart", name = "enabled", havingValue = "true")
public class GenerateChartTool implements AgentTool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "generate_chart",
            "使用当前数据库的完整只读查询结果生成真实 PNG 图表。聚合、排序和 Top N 必须在 SQL 中完成；series 是类别分组维度，不是数值字段",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "sql", Map.of("type", "string", "description", "为图表准备完整数据的一条只读 SELECT 或 WITH 查询"),
                            "chart_type", Map.of("type", "string", "description", "bar、horizontal_bar、line、scatter、histogram 或 box"),
                            "title", Map.of("type", "string", "description", "图表标题"),
                            "x", Map.of("type", "string", "description", "类别或横轴字段；horizontal_bar 中仍传类别字段；histogram 中传数值字段；box 中可作为分组字段"),
                            "y", Map.of("type", "string", "description", "数值指标字段；horizontal_bar 中仍传数值字段；histogram 不需要"),
                            "series", Map.of("type", "string", "description", "可选类别分组维度。line 中每个 series 值生成一条线；同一 x 和 series 组合必须唯一"),
                            "x_label", Map.of("type", "string", "description", "可选 x 字段显示名称和单位；horizontal_bar 中显示在纵轴"),
                            "y_label", Map.of("type", "string", "description", "可选 y 字段显示名称和单位；horizontal_bar 中显示在横轴"),
                            "bins", Map.of("type", "integer", "description", "仅 histogram 使用，范围 5 到 100")),
                    "required", List.of("sql", "chart_type", "title"),
                    "additionalProperties", false));

    private final ReadonlySqlValidator sqlValidator;
    private final ReadonlyQueryExecutor queryExecutor;
    private final ChartDataValidator dataValidator;
    private final ChartGenerationRunner chartRunner;
    private final ChartArtifactStore artifactStore;
    private final ChartGenerationProperties properties;
    private final ObjectMapper objectMapper;

    public GenerateChartTool(
            ReadonlySqlValidator sqlValidator,
            ReadonlyQueryExecutor queryExecutor,
            ChartDataValidator dataValidator,
            ChartGenerationRunner chartRunner,
            ChartArtifactStore artifactStore,
            ChartGenerationProperties properties,
            ObjectMapper objectMapper) {
        this.sqlValidator = sqlValidator;
        this.queryExecutor = queryExecutor;
        this.dataValidator = dataValidator;
        this.chartRunner = chartRunner;
        this.artifactStore = artifactStore;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        long startedAt = System.nanoTime();
        JsonNode arguments = invocation.arguments();
        String sql = text(arguments, "sql");
        ChartDataValidator.ChartSpec spec = new ChartDataValidator.ChartSpec(
                text(arguments, "chart_type"), text(arguments, "title"),
                text(arguments, "x"), text(arguments, "y"), text(arguments, "series"),
                text(arguments, "x_label"), text(arguments, "y_label"),
                arguments.has("bins") ? arguments.path("bins").intValue() : null);
        try {
            chartRunner.verifyAvailable();
            ReadonlySqlValidator.ValidatedQuery query = sqlValidator.validate(sql);
            if (!query.maskedColumnIndexes().isEmpty()) {
                return ToolResult.failure(
                        "SENSITIVE_FIELD_NOT_CHARTABLE",
                        "图表不能直接使用需脱敏字段，请移除这些字段或只查询安全的聚合结果。");
            }
            ReadonlyQueryExecutor.AnalysisDataset dataset = queryExecutor.executeForAnalysis(
                    query, properties.getMaxInputRows(), properties.getMaxInputBytes(),
                    properties.getQueryTimeoutSeconds());
            ChartDataValidator.ValidatedChart validated = dataValidator.validate(dataset.json(), spec);
            ChartGenerationRunner.ChartRunResult run = chartRunner.run(dataset.json(), validated);
            ChartArtifactStore.ChartArtifact artifact = artifactStore.save(
                    run.png(), run.width(), run.height());
            String url = "/api/artifacts/charts/" + artifact.id();
            ObjectNode output = objectMapper.createObjectNode();
            ObjectNode artifactNode = output.putObject("artifact");
            artifactNode.put("id", artifact.id());
            artifactNode.put("type", "chart");
            artifactNode.put("format", "png");
            artifactNode.put("contentType", "image/png");
            artifactNode.put("url", url);
            artifactNode.put("markdown", "![" + markdownAlt(validated.spec().title()) + "](" + url + ")");
            artifactNode.put("width", artifact.width());
            artifactNode.put("height", artifact.height());
            artifactNode.put("sizeBytes", artifact.sizeBytes());
            ObjectNode chart = output.putObject("chart");
            chart.put("type", validated.spec().chartType());
            chart.put("title", validated.spec().title());
            nullable(chart, "x", validated.spec().x());
            nullable(chart, "y", validated.spec().y());
            nullable(chart, "series", validated.spec().series());
            ObjectNode data = output.putObject("data");
            data.put("rowCount", dataset.rowCount());
            data.put("validRows", validated.validRows());
            data.put("droppedRows", validated.droppedRows());
            data.put("complete", true);
            output.set("warnings", objectMapper.valueToTree(run.warnings()));
            ObjectNode timing = output.putObject("timing");
            timing.put("queryMs", dataset.queryMs());
            timing.put("renderMs", run.renderMs());
            timing.put("totalToolMs", elapsedMillis(startedAt));
            return ToolResult.success(new String(
                    objectMapper.writeValueAsBytes(output), StandardCharsets.UTF_8));
        } catch (ReadonlySqlValidator.SqlValidationException e) {
            return ToolResult.failure(e.errorCode(), e.getMessage());
        } catch (ReadonlyQueryExecutor.QueryExecutionException e) {
            return ToolResult.failure(e.errorCode(), e.getMessage());
        } catch (ChartDataValidator.ChartValidationException e) {
            return ToolResult.failure(e.errorCode(), e.getMessage());
        } catch (ChartGenerationRunner.ChartGenerationException e) {
            return ToolResult.failure(e.errorCode(), e.getMessage());
        } catch (ChartArtifactStore.ArtifactStoreException e) {
            return ToolResult.failure(e.errorCode(), e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure("CHART_OUTPUT_INVALID", "图表结果无法转换为统一 Tool Result。");
        }
    }

    private String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? "" : value.asText();
    }
    private void nullable(ObjectNode node, String name, String value) {
        if (value == null || value.isBlank()) node.putNull(name); else node.put(name, value);
    }
    private String markdownAlt(String value) { return value.replace("[", "").replace("]", ""); }
    private long elapsedMillis(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }
}
