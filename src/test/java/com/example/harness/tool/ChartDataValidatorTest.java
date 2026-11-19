package com.example.harness.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class ChartDataValidatorTest {

    private final ChartGenerationProperties properties = new ChartGenerationProperties();
    private final ChartDataValidator validator = new ChartDataValidator(new ObjectMapper(), properties);

    @Test
    void acceptsSeriesAsGroupingDimensionWhenKeysAreUnique() throws Exception {
        ChartDataValidator.ValidatedChart chart = validator.validate(dataset(
                "[{\"month\":\"1月\",\"region\":\"华东\",\"sales\":100}," +
                "{\"month\":\"1月\",\"region\":\"华南\",\"sales\":120}," +
                "{\"month\":\"2月\",\"region\":\"华东\",\"sales\":150}]"),
                spec("line", "month", "sales", "region"));

        assertEquals(3, chart.validRows());
        assertEquals(0, chart.droppedRows());
    }

    @Test
    void rejectsDuplicateAggregateKeyInsteadOfImplicitlyAggregating() {
        ChartDataValidator.ChartValidationException error = failure(dataset(
                "[{\"month\":\"1月\",\"region\":\"华东\",\"sales\":100}," +
                "{\"month\":\"1月\",\"region\":\"华东\",\"sales\":130}]"),
                spec("line", "month", "sales", "region"));

        assertEquals("CHART_DATA_NOT_AGGREGATED", error.errorCode());
    }

    @Test
    void allowsDuplicateScatterObservationsAndRejectsNonNumericY() throws Exception {
        ChartDataValidator.ValidatedChart chart = validator.validate(dataset(
                "[{\"month\":\"1月\",\"region\":\"华东\",\"sales\":100,\"score\":47}," +
                "{\"month\":\"1月\",\"region\":\"华东\",\"sales\":100,\"score\":47}]", true),
                spec("scatter", "sales", "score", "region"));
        assertEquals(2, chart.validRows());

        ChartDataValidator.ChartValidationException error = failure(
                dataset("[{\"month\":\"1月\",\"region\":\"华东\",\"sales\":100}]"),
                spec("bar", "month", "region", ""));
        assertEquals("CHART_FIELD_TYPE_INVALID", error.errorCode());
    }

    private ChartDataValidator.ChartSpec spec(String type, String x, String y, String series) {
        return new ChartDataValidator.ChartSpec(type, "测试图表", x, y, series, "", "", 20);
    }

    private byte[] dataset(String rows) { return dataset(rows, false); }

    private byte[] dataset(String rows, boolean withScore) {
        String extraColumn = withScore ? ",{\"name\":\"score\",\"jdbcType\":4}" : "";
        String json = "{\"columns\":[" +
                "{\"name\":\"month\",\"jdbcType\":12}," +
                "{\"name\":\"region\",\"jdbcType\":12}," +
                "{\"name\":\"sales\",\"jdbcType\":4}" + extraColumn +
                "],\"rows\":" + rows + ",\"rowCount\":2}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private ChartDataValidator.ChartValidationException failure(
            byte[] dataset, ChartDataValidator.ChartSpec spec) {
        try {
            validator.validate(dataset, spec);
            fail("expected ChartValidationException");
            return null;
        } catch (ChartDataValidator.ChartValidationException e) {
            return e;
        }
    }
}
