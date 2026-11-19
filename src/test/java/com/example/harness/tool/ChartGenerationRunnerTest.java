package com.example.harness.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChartGenerationRunnerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validatesPngAndReturnsDimensions() throws Exception {
        PythonProcessRunner processRunner = mock(PythonProcessRunner.class);
        when(processRunner.execute(any(), any())).thenReturn(new PythonProcessResult(
                0, "{\"warnings\":[]}".getBytes(StandardCharsets.UTF_8), new byte[0],
                Map.of("chart.png", png(120, 72)), 25));
        ChartGenerationRunner runner = new ChartGenerationRunner(
                processRunner, properties(), objectMapper);

        ChartGenerationRunner.ChartRunResult result = runner.run(
                dataset(), validatedChart());

        assertEquals(120, result.width());
        assertEquals(72, result.height());
        assertEquals(25, result.renderMs());
    }

    @Test
    void rejectsInvalidPngAndClassifiesMissingDependency() throws Exception {
        PythonProcessRunner invalid = mock(PythonProcessRunner.class);
        when(invalid.execute(any(), any())).thenReturn(new PythonProcessResult(
                0, "{}".getBytes(StandardCharsets.UTF_8), new byte[0],
                Map.of("chart.png", "not-png".getBytes(StandardCharsets.UTF_8)), 1));
        assertEquals("CHART_OUTPUT_INVALID", failure(new ChartGenerationRunner(
                invalid, properties(), objectMapper)).errorCode());

        PythonProcessRunner missing = mock(PythonProcessRunner.class);
        when(missing.execute(any(), any())).thenReturn(new PythonProcessResult(
                1, new byte[0], "ModuleNotFoundError: No module named 'matplotlib'"
                        .getBytes(StandardCharsets.UTF_8), Map.of(), 1));
        assertEquals("CHART_DEPENDENCY_MISSING", failure(new ChartGenerationRunner(
                missing, properties(), objectMapper)).errorCode());
    }

    private ChartGenerationRunner.ChartGenerationException failure(ChartGenerationRunner runner) {
        try {
            runner.run(dataset(), validatedChart());
            fail("expected ChartGenerationException");
            return null;
        } catch (ChartGenerationRunner.ChartGenerationException e) {
            return e;
        }
    }

    private ChartGenerationProperties properties() {
        ChartGenerationProperties value = new ChartGenerationProperties();
        value.setMaxImageBytes(100_000);
        return value;
    }

    private ChartDataValidator.ValidatedChart validatedChart() {
        return new ChartDataValidator.ValidatedChart(new ChartDataValidator.ChartSpec(
                "bar", "测试", "name", "value", "", "", "", 20), 1, 0);
    }

    private byte[] dataset() {
        return "{\"columns\":[],\"rows\":[],\"rowCount\":0}"
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
