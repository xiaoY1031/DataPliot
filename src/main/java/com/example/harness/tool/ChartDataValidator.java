package com.example.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.Types;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** 校验图表字段类型、基数和聚合结果唯一性，不替模型执行任何聚合。 */
@Component
public class ChartDataValidator {

    private static final Set<String> TYPES = Set.of(
            "bar", "horizontal_bar", "line", "scatter", "histogram", "box");
    private static final Set<Integer> NUMERIC_TYPES = Set.of(
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
            Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL);

    private final ObjectMapper objectMapper;
    private final ChartGenerationProperties properties;

    public ChartDataValidator(ObjectMapper objectMapper, ChartGenerationProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public ValidatedChart validate(byte[] datasetJson, ChartSpec spec)
            throws ChartValidationException {
        validateConfiguration();
        String type = normalize(spec.chartType());
        if (!TYPES.contains(type)) {
            throw new ChartValidationException(
                    "CHART_TYPE_UNSUPPORTED", "不支持的图表类型：" + spec.chartType());
        }
        if (blank(spec.title())) {
            throw new ChartValidationException("CHART_SPEC_INVALID", "图表标题不能为空。");
        }
        try {
            JsonNode dataset = objectMapper.readTree(datasetJson);
            JsonNode columns = dataset.path("columns");
            JsonNode rows = dataset.path("rows");
            if (!columns.isArray() || !rows.isArray()) {
                throw new ChartValidationException("CHART_DATA_INVALID", "图表数据集结构无效。");
            }
            Set<String> names = new HashSet<>();
            Set<String> numeric = new HashSet<>();
            columns.forEach(column -> {
                String name = column.path("name").asText();
                names.add(name);
                if (NUMERIC_TYPES.contains(column.path("jdbcType").asInt(Integer.MIN_VALUE))) {
                    numeric.add(name);
                }
            });
            validateFields(type, spec, names, numeric);
            return validateRows(type, spec, rows);
        } catch (ChartValidationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ChartValidationException("CHART_DATA_INVALID", "图表数据集不是有效 JSON。");
        }
    }

    private void validateFields(
            String type, ChartSpec spec, Set<String> names, Set<String> numeric)
            throws ChartValidationException {
        if ("histogram".equals(type)) {
            requireField(spec.x(), "x", names);
            requireNumeric(spec.x(), "x", numeric);
            if (!blank(spec.series())) {
                throw new ChartValidationException(
                        "CHART_SPEC_INVALID", "histogram 首版不支持 series。");
            }
            int bins = spec.bins() == null ? 20 : spec.bins();
            if (bins < 5 || bins > 100) {
                throw new ChartValidationException(
                        "CHART_SPEC_INVALID", "bins 必须在 5 到 100 之间。");
            }
            return;
        }
        if ("box".equals(type)) {
            requireField(spec.y(), "y", names);
            requireNumeric(spec.y(), "y", numeric);
            optionalField(spec.x(), "x", names);
            if (!blank(spec.series())) {
                throw new ChartValidationException(
                        "CHART_SPEC_INVALID", "box 首版不支持 series；请使用 x 作为分组字段。");
            }
            return;
        }
        requireField(spec.x(), "x", names);
        requireField(spec.y(), "y", names);
        requireNumeric(spec.y(), "y", numeric);
        optionalField(spec.series(), "series", names);
        if ("scatter".equals(type)) {
            requireNumeric(spec.x(), "x", numeric);
        }
    }

    private ValidatedChart validateRows(String type, ChartSpec spec, JsonNode rows)
            throws ChartValidationException {
        Set<String> categories = new LinkedHashSet<>();
        Set<String> seriesValues = new LinkedHashSet<>();
        Set<String> keys = new HashSet<>();
        int valid = 0;
        int dropped = 0;
        for (JsonNode row : rows) {
            JsonNode x = blank(spec.x()) ? null : row.get(spec.x());
            JsonNode y = blank(spec.y()) ? null : row.get(spec.y());
            JsonNode series = blank(spec.series()) ? null : row.get(spec.series());
            boolean requiredMissing = "histogram".equals(type)
                    ? missing(x)
                    : "box".equals(type)
                            ? missing(y) || (!blank(spec.x()) && missing(x))
                            : missing(x) || missing(y) || (!blank(spec.series()) && missing(series));
            if (requiredMissing) {
                dropped++;
                continue;
            }
            valid++;
            if (x != null) {
                categories.add(x.toString());
            }
            if (series != null) {
                seriesValues.add(series.toString());
            }
            if ("bar".equals(type) || "horizontal_bar".equals(type) || "line".equals(type)) {
                String key = x.toString() + "\u0000" + (series == null ? "" : series.toString());
                if (!keys.add(key)) {
                    throw new ChartValidationException(
                            "CHART_DATA_NOT_AGGREGATED",
                            "同一个 x 和 series 组合存在多条记录，请先在 SQL 中完成聚合。");
                }
            }
        }
        if (valid == 0) {
            throw new ChartValidationException("CHART_DATA_INVALID", "图表没有可绘制的有效数据。");
        }
        if (("bar".equals(type) || "horizontal_bar".equals(type) || "line".equals(type)
                || "box".equals(type)) && categories.size() > properties.getMaxCategories()) {
            throw new ChartValidationException(
                    "CHART_DATA_TOO_COMPLEX",
                    "图表类别数量超过限制 " + properties.getMaxCategories() + "，请在 SQL 中筛选 Top N。");
        }
        if (seriesValues.size() > properties.getMaxSeries()) {
            throw new ChartValidationException(
                    "CHART_DATA_TOO_COMPLEX",
                    "图表 series 数量超过限制 " + properties.getMaxSeries() + "。");
        }
        if (valid > properties.getMaxPoints()) {
            throw new ChartValidationException(
                    "CHART_DATA_TOO_COMPLEX",
                    "图表有效数据点超过限制 " + properties.getMaxPoints() + "。");
        }
        ChartSpec normalized = new ChartSpec(
                type, spec.title().trim(), trim(spec.x()), trim(spec.y()), trim(spec.series()),
                trim(spec.xLabel()), trim(spec.yLabel()), spec.bins() == null ? 20 : spec.bins());
        return new ValidatedChart(normalized, valid, dropped);
    }

    private void validateConfiguration() throws ChartValidationException {
        if (properties.getMaxCategories() <= 0
                || properties.getMaxSeries() <= 0
                || properties.getMaxPoints() <= 0) {
            throw new ChartValidationException(
                    "CHART_CONFIGURATION_INVALID", "图表类别、系列和数据点限制必须大于 0。");
        }
    }

    private void requireField(String value, String argument, Set<String> names)
            throws ChartValidationException {
        if (blank(value)) {
            throw new ChartValidationException("CHART_SPEC_INVALID", "图表参数 " + argument + " 不能为空。");
        }
        optionalField(value, argument, names);
    }

    private void optionalField(String value, String argument, Set<String> names)
            throws ChartValidationException {
        if (!blank(value) && !names.contains(value.trim())) {
            throw new ChartValidationException(
                    "CHART_FIELD_NOT_FOUND", "图表参数 " + argument + " 对应的字段不存在：" + value);
        }
    }

    private void requireNumeric(String value, String argument, Set<String> numeric)
            throws ChartValidationException {
        if (!numeric.contains(value.trim())) {
            throw new ChartValidationException(
                    "CHART_FIELD_TYPE_INVALID", "图表参数 " + argument + " 必须引用数值字段：" + value);
        }
    }

    private boolean missing(JsonNode value) {
        return value == null || value.isNull() || (value.isTextual() && value.textValue().isBlank());
    }

    private String normalize(String value) { return trim(value).toLowerCase(Locale.ROOT); }
    private String trim(String value) { return value == null ? "" : value.trim(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }

    public record ChartSpec(
            String chartType, String title, String x, String y, String series,
            String xLabel, String yLabel, Integer bins) {
    }

    public record ValidatedChart(ChartSpec spec, int validRows, int droppedRows) {
    }

    public static class ChartValidationException extends Exception {
        private final String errorCode;
        public ChartValidationException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
        public String errorCode() { return errorCode; }
    }
}
