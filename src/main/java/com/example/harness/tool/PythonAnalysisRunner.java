package com.example.harness.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * 执行受限 Python 分析协议。静态检查和进程治理只用于降低常见误用风险，
 * 不构成可安全执行任意不可信代码的操作系统沙箱。
 */
@Component
@ConditionalOnProperty(
        prefix = "datapilot.python",
        name = "enabled",
        havingValue = "true")
public class PythonAnalysisRunner {

    private static final int MAX_JSON_DEPTH = 20;
    private static final int MAX_CONTAINER_ITEMS = 10_000;
    private static final int MAX_STRING_LENGTH = 20_000;

    private final PythonAnalysisProperties properties;
    private final ObjectMapper objectMapper;
    private final PythonProcessRunner processRunner;

    @Autowired
    public PythonAnalysisRunner(
            PythonAnalysisProperties properties,
            ObjectMapper objectMapper,
            PythonProcessRunner processRunner) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.processRunner = processRunner;
    }

    /** 保留给既有单元测试的构造入口；生产环境由 Spring 注入公共 Runner。 */
    PythonAnalysisRunner(PythonAnalysisProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.processRunner = new PythonProcessRunner(properties) {
            @Override
            protected Process startProcess(Path runnerScript, Path temporaryDirectory)
                    throws IOException {
                return PythonAnalysisRunner.this.startProcess(runnerScript, temporaryDirectory);
            }
        };
    }

    protected Process startProcess(Path runnerScript, Path temporaryDirectory) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                properties.getExecutable(), "-I", runnerScript.toString());
        builder.directory(temporaryDirectory.toFile());
        processRunner.configureEnvironment(builder.environment(), temporaryDirectory);
        return builder.start();
    }

    void configureEnvironment(Map<String, String> environment, Path temporaryDirectory) {
        processRunner.configureEnvironment(environment, temporaryDirectory);
    }

    public PythonRunResult run(byte[] datasetJson, String code) throws PythonRunnerException {
        verifyAvailable();
        if (code == null || code.isBlank()) {
            throw new PythonRunnerException("PYTHON_CODE_REJECTED", "Python 分析代码不能为空。");
        }
        if (code.length() > properties.getMaxCodeLength()) {
            throw new PythonRunnerException(
                    "PYTHON_CODE_REJECTED",
                    "Python 分析代码长度不能超过 " + properties.getMaxCodeLength() + " 个字符。");
        }
        if (datasetJson.length > properties.getMaxInputBytes()) {
            throw new PythonRunnerException(
                    "DATASET_LIMIT_EXCEEDED", "Python 分析输入超过配置的最大字节数。");
        }

        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.set("dataset", objectMapper.readTree(datasetJson));
            request.put("code", code);
            byte[] requestBytes = objectMapper.writeValueAsBytes(request);
            int maxStdinBytes = Math.addExact(
                    Math.addExact(properties.getMaxInputBytes(), properties.getMaxCodeLength()),
                    16_384);
            PythonProcessSpec spec = new PythonProcessSpec(
                    "python/datapilot_analysis_runner.py",
                    "datapilot_analysis_runner.py",
                    "datapilot-python-analysis-",
                    properties.getTimeoutSeconds(),
                    maxStdinBytes,
                    properties.getMaxOutputBytes(),
                    properties.getMaxStderrBytes(),
                    Map.of());
            PythonProcessResult process = processRunner.execute(spec, requestBytes);
            if (process.exitCode() != 0) {
                PythonFailure failure = classifyPythonFailure(process.stderr());
                throw new PythonRunnerException(failure.errorCode(), failure.message());
            }
            JsonNode root = parseAndValidate(process.stdout());
            return new PythonRunResult(root, process.processMs());
        } catch (PythonRunnerException e) {
            throw e;
        } catch (PythonProcessException e) {
            throw new PythonRunnerException(
                    e.errorCode(), e.getMessage());
        } catch (IOException | ArithmeticException e) {
            throw new PythonRunnerException(
                    "PYTHON_RESULT_INVALID", "Python 分析输入或输出无法转换为标准 JSON。");
        }
    }

    public void verifyAvailable() throws PythonRunnerException {
        if (properties.getTimeoutSeconds() <= 0
                || properties.getQueryTimeoutSeconds() <= 0
                || properties.getMaxCodeLength() <= 0
                || properties.getMaxInputRows() <= 0
                || properties.getMaxInputBytes() <= 0
                || properties.getMaxOutputBytes() <= 0
                || properties.getMaxStderrBytes() <= 0) {
            throw new PythonRunnerException(
                    "PYTHON_CONFIGURATION_INVALID",
                    "Python 分析的超时、代码长度和输入输出限制必须大于 0。");
        }
        try {
            processRunner.verifyInterpreterAvailable();
        } catch (PythonProcessException e) {
            throw new PythonRunnerException(e.errorCode(), e.getMessage());
        }
    }

    private JsonNode parseAndValidate(byte[] stdout) throws PythonRunnerException {
        try {
            JsonParser parser = objectMapper.getFactory().createParser(stdout);
            JsonNode root = objectMapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw new PythonRunnerException(
                        "PYTHON_RESULT_INVALID", "Python 必须只输出一个 JSON 对象。");
            }
            JsonNode result = root.get("result");
            if (result == null || !result.isObject()) {
                throw new PythonRunnerException(
                        "PYTHON_RESULT_INVALID", "Python 输出必须包含 JSON 对象类型的 result。");
            }
            validateJsonShape(root, 0);
            byte[] normalized = objectMapper.writeValueAsBytes(root);
            if (normalized.length > properties.getMaxOutputBytes()) {
                throw new PythonRunnerException(
                        "PYTHON_OUTPUT_TOO_LARGE", "Python 结构化结果超过配置的最大字节数。");
            }
            return objectMapper.readTree(normalized);
        } catch (PythonRunnerException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new PythonRunnerException(
                    "PYTHON_RESULT_INVALID", "Python 输出不是有效的标准 JSON。");
        }
    }

    private void validateJsonShape(JsonNode node, int depth) throws PythonRunnerException {
        if (depth > MAX_JSON_DEPTH) {
            throw new PythonRunnerException(
                    "PYTHON_RESULT_INVALID", "Python 结果嵌套层级过深。");
        }
        if (node.isTextual() && node.textValue().length() > MAX_STRING_LENGTH) {
            throw new PythonRunnerException(
                    "PYTHON_RESULT_INVALID", "Python 结果包含过长字符串。");
        }
        if (node.isContainerNode() && node.size() > MAX_CONTAINER_ITEMS) {
            throw new PythonRunnerException(
                    "PYTHON_RESULT_INVALID", "Python 结果包含过多对象字段或数组元素。");
        }
        if (node.isContainerNode()) {
            var children = node.elements();
            while (children.hasNext()) {
                validateJsonShape(children.next(), depth + 1);
            }
        }
    }

    private boolean dependencyMissing(byte[] stderr) {
        String value = new String(stderr, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return value.contains("module_not_found")
                || value.contains("modulenotfounderror")
                || value.contains("no module named");
    }

    private PythonFailure classifyPythonFailure(byte[] stderr) {
        String value = new String(stderr, StandardCharsets.UTF_8);
        if (value.contains("PYTHON_CODE_REJECTED")) {
            return new PythonFailure(
                    "PYTHON_CODE_REJECTED",
                    "Python 代码未通过受限执行策略检查。");
        }
        if (value.contains("PYTHON_RESULT_MISSING")) {
            return new PythonFailure(
                    "PYTHON_RESULT_MISSING",
                    "Python 代码必须为 result 变量赋一个 JSON 对象。");
        }
        if (value.contains("PYTHON_RESULT_INVALID")) {
            return new PythonFailure(
                    "PYTHON_RESULT_INVALID",
                    "Python result 无法转换为标准 JSON 对象。");
        }
        if (dependencyMissing(stderr)) {
            return new PythonFailure(
                    "PYTHON_DEPENDENCY_MISSING",
                    "Python 环境缺少 pandas、numpy 或 scipy 依赖。");
        }
        return new PythonFailure(
                "PYTHON_EXECUTION_ERROR",
                "Python 分析执行失败，请检查分析代码和输入字段。");
    }

    public record PythonRunResult(JsonNode root, long pythonProcessMs) {
    }

    private record PythonFailure(String errorCode, String message) {
    }

    public static class PythonRunnerException extends Exception {
        private final String errorCode;

        public PythonRunnerException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }

}
