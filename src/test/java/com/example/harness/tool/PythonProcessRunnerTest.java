package com.example.harness.tool;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PythonProcessRunnerTest {

    @Test
    void startsPythonInIsolatedUtf8Mode() {
        String executable = Path.of("python").toString();
        PythonProcessRunner runner = new PythonProcessRunner(properties(executable));
        Path script = Path.of("runner.py");

        assertEquals(
                List.of(executable, "-I", "-X", "utf8", script.toString()),
                runner.processCommand(script));
    }

    @Test
    void usesEnvironmentAllowlistAndDropsSecrets() {
        PythonProcessRunner runner = new PythonProcessRunner(properties(javaExecutable().toString()));
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "safe");
        environment.put("DATAPILOT_DB_PASSWORD", "secret");
        environment.put("LLM_API_KEY", "secret");
        Path temporary = Path.of("target", "python-process-env").toAbsolutePath();

        runner.configureEnvironment(environment, temporary);

        assertEquals("safe", environment.get("PATH"));
        assertEquals(temporary.toString(), environment.get("TEMP"));
        assertEquals(temporary.resolve("matplotlib").toString(), environment.get("MPLCONFIGDIR"));
        assertFalse(environment.containsKey("DATAPILOT_DB_PASSWORD"));
        assertFalse(environment.containsKey("LLM_API_KEY"));
    }

    @Test
    void readsStdoutStderrAndExpectedOutputFileBeforeCleanup() throws Exception {
        PythonProcessRunner runner = fakeRunner("normal");
        PythonProcessSpec spec = spec(5, 4096, 4096, Map.of("chart.png", 4096));

        PythonProcessResult result = runner.execute(spec, "request".getBytes(StandardCharsets.UTF_8));

        assertEquals(0, result.exitCode());
        assertEquals("stdout", new String(result.stdout(), StandardCharsets.UTF_8));
        assertTrue(new String(result.stderr(), StandardCharsets.UTF_8).endsWith("stderr"));
        assertArrayEquals("png".getBytes(StandardCharsets.UTF_8), result.outputFiles().get("chart.png"));
    }

    @Test
    void terminatesTimeoutAndOutputOverflow() {
        assertEquals("PYTHON_TIMEOUT", failure(fakeRunner("timeout"), spec(1, 4096, 4096, Map.of())).errorCode());
        assertEquals("PYTHON_OUTPUT_TOO_LARGE", failure(
                fakeRunner("large-stdout"), spec(5, 128, 4096, Map.of())).errorCode());
        assertEquals("PYTHON_OUTPUT_TOO_LARGE", failure(
                fakeRunner("large-file"), spec(5, 4096, 4096, Map.of("chart.png", 128))).errorCode());
    }

    private PythonProcessRunner fakeRunner(String mode) {
        PythonAnalysisProperties properties = properties(javaExecutable().toString());
        return new PythonProcessRunner(properties) {
            @Override
            protected Process startProcess(Path runnerScript, Path temporaryDirectory)
                    throws java.io.IOException {
                return new ProcessBuilder(
                        javaExecutable().toString(), "-cp", absoluteClasspath(),
                        FakeProcess.class.getName(), mode)
                        .directory(temporaryDirectory.toFile())
                        .start();
            }
        };
    }

    private PythonProcessSpec spec(
            int timeout, int stdout, int stderr, Map<String, Integer> files) {
        return new PythonProcessSpec(
                "python/datapilot_analysis_runner.py", "runner.py",
                "datapilot-process-test-", timeout, 4096, stdout, stderr, files);
    }

    private PythonProcessException failure(PythonProcessRunner runner, PythonProcessSpec spec) {
        try {
            runner.execute(spec, new byte[0]);
            fail("expected PythonProcessException");
            return null;
        } catch (PythonProcessException e) {
            return e;
        }
    }

    private PythonAnalysisProperties properties(String executable) {
        PythonAnalysisProperties properties = new PythonAnalysisProperties();
        properties.setExecutable(executable);
        return properties;
    }

    private Path javaExecutable() {
        String name = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name);
    }

    private String absoluteClasspath() {
        String separator = System.getProperty("path.separator");
        return java.util.Arrays.stream(System.getProperty("java.class.path").split(
                        java.util.regex.Pattern.quote(separator)))
                .map(Path::of).map(path -> path.toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(separator));
    }

    public static class FakeProcess {
        public static void main(String[] args) throws Exception {
            System.in.readAllBytes();
            switch (args[0]) {
                case "normal" -> {
                    java.nio.file.Files.writeString(Path.of("chart.png"), "png");
                    System.out.print("stdout");
                    System.err.print("stderr");
                }
                case "large-stdout" -> System.out.print("x".repeat(32_000));
                case "large-file" -> java.nio.file.Files.writeString(Path.of("chart.png"), "x".repeat(32_000));
                case "timeout" -> Thread.sleep(10_000);
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
}
