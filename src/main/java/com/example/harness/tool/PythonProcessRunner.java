package com.example.harness.tool;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Python 子进程的公共通信与资源治理层。它不解释任何业务协议，也不构成
 * 能够安全执行任意不可信代码的操作系统沙箱。
 */
@Component
public class PythonProcessRunner {

    private static final Set<String> ENVIRONMENT_ALLOWLIST = Set.of(
            "PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATHEXT", "COMSPEC");

    private final PythonAnalysisProperties pythonProperties;

    public PythonProcessRunner(PythonAnalysisProperties pythonProperties) {
        this.pythonProperties = pythonProperties;
    }

    public void verifyInterpreterAvailable() throws PythonProcessException {
        if (pythonProperties.getExecutable().isBlank()) {
            throw new PythonProcessException(
                    "PYTHON_NOT_CONFIGURED", "未配置 Python 解释器路径。");
        }
        Path executable;
        try {
            executable = Path.of(pythonProperties.getExecutable());
        } catch (RuntimeException e) {
            throw new PythonProcessException(
                    "PYTHON_NOT_AVAILABLE", "配置的 Python 解释器路径无效。");
        }
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new PythonProcessException(
                    "PYTHON_NOT_AVAILABLE", "配置的 Python 解释器不存在或不可执行。");
        }
    }

    public PythonProcessResult execute(PythonProcessSpec spec, byte[] stdinBytes)
            throws PythonProcessException {
        validateSpec(spec);
        verifyInterpreterAvailable();
        byte[] input = stdinBytes == null ? new byte[0] : stdinBytes;
        if (input.length > spec.maxStdinBytes()) {
            throw new PythonProcessException(
                    "PYTHON_INPUT_TOO_LARGE", "Python 进程输入超过配置的最大字节数。");
        }

        Path temporaryDirectory = null;
        Process process = null;
        ExecutorService ioExecutor = null;
        try {
            temporaryDirectory = Files.createTempDirectory(spec.temporaryDirectoryPrefix());
            Path runnerScript = copyRunnerScript(spec, temporaryDirectory);
            long processStartedAt = System.nanoTime();
            process = startProcess(runnerScript, temporaryDirectory);
            ioExecutor = newIoExecutor();

            Process activeProcess = process;
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(
                    () -> readBounded(activeProcess.getInputStream(), spec.maxStdoutBytes()),
                    ioExecutor);
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(
                    () -> readBounded(activeProcess.getErrorStream(), spec.maxStderrBytes()),
                    ioExecutor);
            CompletableFuture<Void> stdin = CompletableFuture.runAsync(
                    () -> writeInput(activeProcess.getOutputStream(), input), ioExecutor);
            stdout.whenComplete((value, failure) -> terminateOnOutputLimit(activeProcess, failure));
            stderr.whenComplete((value, failure) -> terminateOnOutputLimit(activeProcess, failure));

            boolean exited = process.waitFor(spec.timeoutSeconds(), TimeUnit.SECONDS);
            if (!exited) {
                terminateProcessTree(process);
                throw new PythonProcessException(
                        "PYTHON_TIMEOUT",
                        "Python 进程执行超过 " + spec.timeoutSeconds() + " 秒，已终止。");
            }
            long processMs = elapsedMillis(processStartedAt);
            int exitCode = process.exitValue();
            byte[] stdoutBytes = await(stdout, process);
            byte[] stderrBytes = await(stderr, process);
            if (exitCode == 0) {
                await(stdin.thenApply(value -> Boolean.TRUE), process);
            } else {
                awaitInputBestEffort(stdin);
            }
            Map<String, byte[]> outputFiles = readOutputFiles(temporaryDirectory, spec.outputFiles());
            return new PythonProcessResult(
                    exitCode, stdoutBytes, stderrBytes, outputFiles, processMs);
        } catch (PythonProcessException e) {
            throw e;
        } catch (IOException e) {
            throw new PythonProcessException(
                    "PYTHON_NOT_AVAILABLE",
                    "无法启动配置的 Python 解释器或准备运行脚本。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminateProcessTree(process);
            throw new PythonProcessException(
                    "PYTHON_EXECUTION_ERROR", "Python 进程执行被中断。");
        } finally {
            closeProcessStreams(process);
            if (ioExecutor != null) {
                ioExecutor.shutdownNow();
            }
            deleteRecursively(temporaryDirectory);
        }
    }

    private void validateSpec(PythonProcessSpec spec) throws PythonProcessException {
        if (spec == null
                || blank(spec.scriptResource())
                || blank(spec.scriptFileName())
                || blank(spec.temporaryDirectoryPrefix())
                || spec.timeoutSeconds() <= 0
                || spec.maxStdinBytes() <= 0
                || spec.maxStdoutBytes() <= 0
                || spec.maxStderrBytes() <= 0) {
            throw new PythonProcessException(
                    "PYTHON_CONFIGURATION_INVALID", "Python 进程配置不完整或限制无效。");
        }
        if (!safeFileName(spec.scriptFileName())) {
            throw new PythonProcessException(
                    "PYTHON_CONFIGURATION_INVALID", "Python 脚本文件名无效。");
        }
        for (var entry : spec.outputFiles().entrySet()) {
            if (!safeFileName(entry.getKey()) || entry.getValue() == null || entry.getValue() <= 0) {
                throw new PythonProcessException(
                        "PYTHON_CONFIGURATION_INVALID", "Python 输出文件配置无效。");
            }
        }
    }

    protected Process startProcess(Path runnerScript, Path temporaryDirectory) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(processCommand(runnerScript));
        builder.directory(temporaryDirectory.toFile());
        configureEnvironment(builder.environment(), temporaryDirectory);
        return builder.start();
    }

    List<String> processCommand(Path runnerScript) {
        return List.of(
                pythonProperties.getExecutable(),
                "-I",
                "-X",
                "utf8",
                runnerScript.toString());
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
        environment.put("MPLCONFIGDIR", temporaryDirectory.resolve("matplotlib").toString());
    }

    private Path copyRunnerScript(PythonProcessSpec spec, Path temporaryDirectory)
            throws IOException {
        Path target = temporaryDirectory.resolve(spec.scriptFileName());
        ClassPathResource resource = new ClassPathResource(spec.scriptResource());
        try (InputStream stream = resource.getInputStream()) {
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private Map<String, byte[]> readOutputFiles(
            Path temporaryDirectory, Map<String, Integer> expectedFiles)
            throws IOException, PythonProcessException {
        Map<String, byte[]> files = new HashMap<>();
        for (var entry : expectedFiles.entrySet()) {
            Path path = temporaryDirectory.resolve(entry.getKey()).normalize();
            if (!path.getParent().equals(temporaryDirectory) || !Files.isRegularFile(path)) {
                continue;
            }
            byte[] bytes;
            try (InputStream input = Files.newInputStream(path)) {
                bytes = input.readNBytes(entry.getValue() + 1);
            }
            if (bytes.length > entry.getValue()) {
                throw new PythonProcessException(
                        "PYTHON_OUTPUT_TOO_LARGE",
                        "Python 输出文件超过配置的最大字节数。");
            }
            files.put(entry.getKey(), bytes);
        }
        return Map.copyOf(files);
    }

    private ExecutorService newIoExecutor() {
        return Executors.newFixedThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "datapilot-python-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void writeInput(OutputStream output, byte[] input) {
        try (output) {
            output.write(input);
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
            throws PythonProcessException, InterruptedException {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            terminateProcessTree(process);
            throw new PythonProcessException(
                    "PYTHON_EXECUTION_ERROR", "Python 进程输入输出流未能正常结束。");
        } catch (ExecutionException e) {
            terminateProcessTree(process);
            if (containsCause(e.getCause(), OutputLimitException.class)) {
                throw new PythonProcessException(
                        "PYTHON_OUTPUT_TOO_LARGE",
                        "Python 标准输出或错误输出超过配置限制，进程已终止。");
            }
            throw new PythonProcessException(
                    "PYTHON_EXECUTION_ERROR", "读取 Python 进程输入输出失败。");
        }
    }

    private void terminateOnOutputLimit(Process process, Throwable failure) {
        if (containsCause(failure, OutputLimitException.class)) {
            terminateProcessTree(process);
        }
    }

    private void awaitInputBestEffort(CompletableFuture<Void> stdin) {
        try {
            stdin.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // 非零退出时优先把 stderr 交给业务 Runner 分类，写入端断开不覆盖真实错误。
        }
    }

    private boolean containsCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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

    private String findIgnoreCase(Map<String, String> values, String name) {
        for (var entry : values.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean safeFileName(String value) {
        try {
            return value != null
                    && !value.isBlank()
                    && Path.of(value).getNameCount() == 1
                    && !value.equals(".")
                    && !value.equals("..");
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private static class OutputLimitException extends RuntimeException {
    }

    private static class AsyncProcessException extends RuntimeException {
        private AsyncProcessException(Throwable cause) {
            super(cause);
        }
    }
}
