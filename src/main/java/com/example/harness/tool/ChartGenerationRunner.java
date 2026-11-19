package com.example.harness.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 固定图表协议 Runner；模型不能向该 Runner 提供 Python 代码。 */
@Component
@ConditionalOnProperty(prefix = "datapilot.chart", name = "enabled", havingValue = "true")
public class ChartGenerationRunner {

    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    private final PythonProcessRunner processRunner;
    private final ChartGenerationProperties properties;
    private final ObjectMapper objectMapper;

    public ChartGenerationRunner(
            PythonProcessRunner processRunner,
            ChartGenerationProperties properties,
            ObjectMapper objectMapper) {
        this.processRunner = processRunner;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void verifyAvailable() throws ChartGenerationException {
        if (properties.getTimeoutSeconds() <= 0
                || properties.getQueryTimeoutSeconds() <= 0
                || properties.getMaxInputRows() <= 0
                || properties.getMaxInputBytes() <= 0
                || properties.getMaxStdoutBytes() <= 0
                || properties.getMaxStderrBytes() <= 0
                || properties.getMaxImageBytes() <= 0
                || properties.getMaxWidth() <= 0
                || properties.getMaxHeight() <= 0) {
            throw new ChartGenerationException(
                    "CHART_CONFIGURATION_INVALID", "图表生成的超时和输入输出限制必须大于 0。");
        }
        try {
            processRunner.verifyInterpreterAvailable();
        } catch (PythonProcessException e) {
            throw new ChartGenerationException(e.errorCode(), e.getMessage());
        }
    }

    public ChartRunResult run(byte[] datasetJson, ChartDataValidator.ValidatedChart chart)
            throws ChartGenerationException {
        verifyAvailable();
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.set("dataset", objectMapper.readTree(datasetJson));
            request.set("chart", objectMapper.valueToTree(chart.spec()));
            byte[] requestBytes = objectMapper.writeValueAsBytes(request);
            int maxStdin = Math.addExact(properties.getMaxInputBytes(), 65_536);
            PythonProcessSpec spec = new PythonProcessSpec(
                    "python/datapilot_chart_runner.py",
                    "datapilot_chart_runner.py",
                    "datapilot-python-chart-",
                    properties.getTimeoutSeconds(),
                    maxStdin,
                    properties.getMaxStdoutBytes(),
                    properties.getMaxStderrBytes(),
                    Map.of("chart.png", properties.getMaxImageBytes()));
            PythonProcessResult process = processRunner.execute(spec, requestBytes);
            if (process.exitCode() != 0) {
                throw classifyFailure(process.stderr());
            }
            JsonNode metadata = parseMetadata(process.stdout());
            byte[] png = process.outputFiles().get("chart.png");
            if (png == null) {
                throw new ChartGenerationException("CHART_OUTPUT_INVALID", "图表进程没有生成 PNG 文件。");
            }
            ImageSize imageSize = validatePng(png);
            JsonNode warningsNode = metadata.path("warnings");
            List<String> warnings = warningsNode.isArray()
                    ? objectMapper.convertValue(warningsNode, objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, String.class))
                    : List.of();
            return new ChartRunResult(png, imageSize.width(), imageSize.height(), warnings, process.processMs());
        } catch (ChartGenerationException e) {
            throw e;
        } catch (PythonProcessException e) {
            throw new ChartGenerationException(e.errorCode(), e.getMessage());
        } catch (IOException | RuntimeException e) {
            throw new ChartGenerationException("CHART_OUTPUT_INVALID", "图表输出无法通过验证。");
        }
    }

    private JsonNode parseMetadata(byte[] stdout) throws ChartGenerationException {
        try {
            JsonParser parser = objectMapper.getFactory().createParser(stdout);
            JsonNode root = objectMapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw new ChartGenerationException("CHART_OUTPUT_INVALID", "图表进程必须只输出一个 JSON 对象。");
            }
            return root;
        } catch (ChartGenerationException e) {
            throw e;
        } catch (IOException e) {
            throw new ChartGenerationException("CHART_OUTPUT_INVALID", "图表进程元数据不是有效 JSON。");
        }
    }

    private ImageSize validatePng(byte[] png) throws ChartGenerationException {
        if (png.length < PNG_SIGNATURE.length) {
            throw new ChartGenerationException("CHART_OUTPUT_INVALID", "图表文件不是合法 PNG。");
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (png[index] != PNG_SIGNATURE[index]) {
                throw new ChartGenerationException("CHART_OUTPUT_INVALID", "图表文件不是合法 PNG。");
            }
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0
                    || image.getWidth() > properties.getMaxWidth()
                    || image.getHeight() > properties.getMaxHeight()) {
                throw new ChartGenerationException("CHART_OUTPUT_INVALID", "图表图片尺寸无效或超过限制。");
            }
            return new ImageSize(image.getWidth(), image.getHeight());
        } catch (IOException e) {
            throw new ChartGenerationException("CHART_OUTPUT_INVALID", "图表 PNG 无法读取。");
        }
    }

    private ChartGenerationException classifyFailure(byte[] stderr) {
        String value = new String(stderr, StandardCharsets.UTF_8);
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.contains("CHART_DEPENDENCY_MISSING")
                || lower.contains("no module named 'matplotlib'")
                || lower.contains("no module named matplotlib")) {
            return new ChartGenerationException(
                    "CHART_DEPENDENCY_MISSING", "Python 环境缺少 matplotlib 依赖。");
        }
        if (value.contains("CHART_DATA_INVALID")) {
            return new ChartGenerationException("CHART_DATA_INVALID", "Python 无法使用当前数据生成图表。");
        }
        return new ChartGenerationException("CHART_RENDER_ERROR", "图表渲染失败，请检查图表规格和数据字段。");
    }

    public record ChartRunResult(
            byte[] png, int width, int height, List<String> warnings, long renderMs) {
        public ChartRunResult { png = png.clone(); warnings = List.copyOf(warnings); }
        @Override public byte[] png() { return png.clone(); }
    }
    private record ImageSize(int width, int height) { }

    public static class ChartGenerationException extends Exception {
        private final String errorCode;
        public ChartGenerationException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
        public String errorCode() { return errorCode; }
    }
}
