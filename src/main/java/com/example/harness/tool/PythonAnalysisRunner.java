package com.example.harness.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 启动本地受限 Python 子进程。静态检查和进程治理只用于降低常见误用风险，
 * 不构成可安全执行任意不可信代码的操作系统沙箱。
 */
@Component
@ConditionalOnProperty(
        prefix = "datapilot.python",
        name = "enabled",
        havingValue = "true")
public class PythonAnalysisRunner {

    private static final Set<String> ENVIRONMENT_ALLOWLIST = Set.of(
            "PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATHEXT", "COMSPEC");
    private static final int MAX_JSON_DEPTH = 20;
    private static final int MAX_CONTAINER_ITEMS = 10_000;
    private static final int MAX_STRING_LENGTH = 20_000;

    private final PythonAnalysisProperties properties;
    private final ObjectMapper objectMapper;

    public PythonAnalysisRunner(PythonAnalysisProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
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

        Path temporaryDirectory = null;
        Process process = null;
        ExecutorService ioExecutor = null;
        try {
            temporaryDirectory = Files.createTempDirectory("datapilot-python-");
            Path runnerScript = copyRunnerScript(temporaryDirectory);
            long processStartedAt = System.nanoTime();
            process = startProcess(runnerScript, temporaryDirectory);
            ioExecutor = newIoExecutor();

            Process activeProcess = process;
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(
                    () -> readBounded(activeProcess.getInputStream(), properties.getMaxOutputBytes()),
                    ioExecutor);
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(
                    () -> readBounded(activeProcess.getErrorStream(), properties.getMaxStderrBytes()),
                    ioExecutor);
            CompletableFuture<Void> stdin = CompletableFuture.runAsync(
                    () -> writeRequest(activeProcess.getOutputStream(), datasetJson, code),
                    ioExecutor);
            stdout.whenComplete((value, failure) -> terminateOnOutputLimit(activeProcess, failure));
            stderr.whenComplete((value, failure) -> terminateOnOutputLimit(activeProcess, failure));

            boolean exited = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!exited) {
                terminateProcessTree(process);
                throw new PythonRunnerException(
                        "PYTHON_TIMEOUT",
                        "Python 分析执行超过 " + properties.getTimeoutSeconds() + " 秒，已终止。");
            }
            long pythonProcessMs = elapsedMillis(processStartedAt);

            byte[] stdoutBytes = await(stdout, process);
            byte[] stderrBytes = await(stderr, process);
            if (process.exitValue() != 0) {
                PythonFailure failure = classifyPythonFailure(stderrBytes);
                throw new PythonRunnerException(failure.errorCode(), failure.message());
            }
            awaitCompletion(stdin, process);

            JsonNode root = parseAndValidate(stdoutBytes);
            return new PythonRunResult(root, pythonProcessMs);
        } catch (PythonRunnerException e) {
            throw e;
        } catch (IOException e) {
            throw new PythonRunnerException(
                    "PYTHON_NOT_AVAILABLE",
                    "无法启动配置的 Python 解释器，请检查解释器路径和本地权限。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                terminateProcessTree(process);
            }
            throw new PythonRunnerException(
                    "PYTHON_EXECUTION_ERROR", "Python 分析执行被中断。");
        } finally {
            closeProcessStreams(process);
            if (ioExecutor != null) {
                ioExecutor.shutdownNow();
            }
            deleteRecursively(temporaryDirectory);
        }
    }

    private ExecutorService newIoExecutor() {
        return Executors.newFixedThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "datapilot-python-io");
            thread.setDaemon(true);
            return thread;
        });
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
        if (properties.getExecutable().isBlank()) {
            throw new PythonRunnerException(
                    "PYTHON_NOT_CONFIGURED", "已启用 Python 分析，但未配置解释器路径。");
        }
        Path executable;
        try {
            executable = Path.of(properties.getExecutable());
        } catch (RuntimeException e) {
            throw new PythonRunnerException(
                    "PYTHON_NOT_AVAILABLE", "配置的 Python 解释器路径无效。");
        }
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new PythonRunnerException(
                    "PYTHON_NOT_AVAILABLE", "配置的 Python 解释器不存在或不可执行。");
        }
    }

    protected Path copyRunnerScript(Path temporaryDirectory) throws IOException {
        Path target = temporaryDirectory.resolve("datapilot_analysis_runner.py");
        ClassPathResource resource = new ClassPathResource(
                "python/datapilot_analysis_runner.py");
        try (InputStream stream = resource.getInputStream()) {
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    protected Process startProcess(Path runnerScript, Path temporaryDirectory) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                properties.getExecutable(), "-I", runnerScript.toString());
        builder.directory(temporaryDirectory.toFile());
        configureEnvironment(builder.environment(), temporaryDirectory);
        return builder.start();
    }

    void configureEnvironment(Map<String, String> environment, Path temporaryDirectory) {
        Map<String, String> source = Map.copyOf(environment);
        environment.clear();
        for (String name : ENVIRONMENT_ALLOWLIST) {
            String value = findIgnoreCase(source, name);
            if (value != null && !value.isBlank()) {
                environment.put(name, value);
            }
        }
        environment.put("TEMP", temporaryDirectory.toString());
        environment.put("TMP", temporaryDirectory.toString());
        environment.put("PYTHONIOENCODING", "utf-8");
        environment.put("PYTHONUTF8", "1");
        environment.put("PYTHONDONTWRITEBYTECODE", "1");
    }

    private String findIgnoreCase(Map<String, String> values, String name) {
        for (var entry : values.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void writeRequest(OutputStream output, byte[] datasetJson, String code) {
        try (output) {
            ObjectNode request = objectMapper.createObjectNode();
            request.set("dataset", objectMapper.readTree(datasetJson));
            request.put("code", code);
            objectMapper.writeValue(output, request);
        } catch (IOException e) {
            throw new AsyncProcessException(e);
        }
    }

    private byte[] readBounded(InputStream stream, int maxBytes) {
        try (stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (output.size() > maxBytes - count) {
                    throw new OutputLimitException();
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new AsyncProcessException(e);
        }
    }

    private <T> T await(CompletableFuture<T> future, Process process)
            throws PythonRunnerException, InterruptedException {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            terminateProcessTree(process);
            throw new PythonRunnerException(
                    "PYTHON_EXECUTION_ERROR", "Python 进程输出流未能正常结束。");
        } catch (ExecutionException e) {
            terminateProcessTree(process);
            Throwable cause = e.getCause();
            if (cause instanceof OutputLimitException) {
                throw new PythonRunnerException(
                        "PYTHON_OUTPUT_TOO_LARGE", "Python 输出超过配置的最大字节数，进程已终止。");
            }
            throw new PythonRunnerException(
                    "PYTHON_EXECUTION_ERROR", "读取 Python 进程输入输出失败。");
        }
    }

    private void terminateOnOutputLimit(Process process, Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof OutputLimitException) {
                terminateProcessTree(process);
                return;
            }
            current = current.getCause();
        }
    }

    private void awaitCompletion(CompletableFuture<Void> future, Process process)
            throws PythonRunnerException, InterruptedException {
        await(future.thenApply(value -> Boolean.TRUE), process);
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

    private void terminateProcessTree(Process process) {
        if (process == null) {
            return;
        }
        ProcessHandle handle = process.toHandle();
        List<ProcessHandle> descendants = handle.descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .toList();
        descendants.forEach(ProcessHandle::destroy);
        handle.destroy();
        waitBriefly(handle, descendants);
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (handle.isAlive()) {
            handle.destroyForcibly();
        }
        waitBriefly(handle, descendants);
    }

    private void waitBriefly(ProcessHandle handle, List<ProcessHandle> descendants) {
        List<CompletableFuture<ProcessHandle>> futures = new ArrayList<>();
        descendants.stream().filter(ProcessHandle::isAlive)
                .forEach(value -> futures.add(value.onExit()));
        if (handle.isAlive()) {
            futures.add(handle.onExit());
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(300, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // 随后执行强制终止。
        }
    }

    private void closeProcessStreams(Process process) {
        if (process == null) {
            return;
        }
        for (var stream : List.of(process.getInputStream(), process.getErrorStream())) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }
    }

    private void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
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

    private static class OutputLimitException extends RuntimeException {
    }

    private static class AsyncProcessException extends RuntimeException {
        private AsyncProcessException(Throwable cause) {
            super(cause);
        }
    }

}
