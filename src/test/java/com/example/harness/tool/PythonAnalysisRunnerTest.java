package com.example.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PythonAnalysisRunnerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void reportsMissingInterpreterConfiguration() {
        PythonAnalysisProperties properties = properties("");
        PythonAnalysisRunner runner = new PythonAnalysisRunner(properties, objectMapper);

        PythonAnalysisRunner.PythonRunnerException error = expectFailure(
                runner, "result = {}");

        assertEquals("PYTHON_NOT_CONFIGURED", error.errorCode());
    }

    @Test
    void reportsUnavailableInterpreter() {
        PythonAnalysisProperties properties = properties(
                Path.of("target", "missing-python").toAbsolutePath().toString());
        PythonAnalysisRunner runner = new PythonAnalysisRunner(properties, objectMapper);

        PythonAnalysisRunner.PythonRunnerException error = expectFailure(
                runner, "result = {}");

        assertEquals("PYTHON_NOT_AVAILABLE", error.errorCode());
    }

    @Test
    void environmentUsesAllowlistAndDropsSecrets() {
        PythonAnalysisProperties properties = properties(javaExecutable().toString());
        PythonAnalysisRunner runner = new PythonAnalysisRunner(properties, objectMapper);
        Path temporaryDirectory = Path.of("target", "python-environment-test")
                .toAbsolutePath();
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "safe-path");
        environment.put("DATAPILOT_DB_PASSWORD", "database-secret");
        environment.put("DEEPSEEK_API_KEY", "model-secret");

        runner.configureEnvironment(environment, temporaryDirectory);

        assertEquals("safe-path", environment.get("PATH"));
        assertEquals(temporaryDirectory.toString(), environment.get("TEMP"));
        assertFalse(environment.containsKey("DATAPILOT_DB_PASSWORD"));
        assertFalse(environment.containsKey("DEEPSEEK_API_KEY"));
    }

    @Test
    void returnsValidatedJsonObjectFromChildProcess() throws Exception {
        PythonAnalysisProperties properties = properties(javaExecutable().toString());
        PythonAnalysisRunner runner = runner(properties, "normal");

        PythonAnalysisRunner.PythonRunResult run = runner.run(dataset(), "ignored");

        assertEquals(3, run.root().path("result").path("value").asInt());
        assertTrue(run.root().path("warnings").isArray());
        assertTrue(run.pythonProcessMs() >= 0);
    }

    @Test
    void rejectsMultipleJsonDocuments() {
        PythonAnalysisProperties properties = properties(javaExecutable().toString());
        PythonAnalysisRunner runner = runner(properties, "multiple-json");

        PythonAnalysisRunner.PythonRunnerException error = expectFailure(runner, "ignored");

        assertEquals("PYTHON_RESULT_INVALID", error.errorCode());
    }

    @Test
    void rejectsMissingOrNonObjectResult() {
        PythonAnalysisProperties properties = properties(javaExecutable().toString());

        assertEquals(
                "PYTHON_RESULT_INVALID",
                expectFailure(runner(properties, "missing-result"), "ignored").errorCode());
        assertEquals(
                "PYTHON_RESULT_INVALID",
                expectFailure(runner(properties, "array-result"), "ignored").errorCode());
    }

    @Test
    void classifiesCodePolicyAndDependencyFailures() {
        PythonAnalysisProperties properties = properties(javaExecutable().toString());

        assertEquals(
                "PYTHON_CODE_REJECTED",
                expectFailure(runner(properties, "code-rejected"), "ignored").errorCode());
        assertEquals(
                "PYTHON_DEPENDENCY_MISSING",
                expectFailure(runner(properties, "dependency-missing"), "ignored").errorCode());
    }

    @Test
    void stopsProcessWhenStdoutOrStderrExceedsLimit() {
        PythonAnalysisProperties stdoutProperties = properties(javaExecutable().toString());
        stdoutProperties.setMaxOutputBytes(512);
        assertEquals(
                "PYTHON_OUTPUT_TOO_LARGE",
                expectFailure(runner(stdoutProperties, "large-stdout"), "ignored").errorCode());

        PythonAnalysisProperties stderrProperties = properties(javaExecutable().toString());
        stderrProperties.setMaxStderrBytes(512);
        assertEquals(
                "PYTHON_OUTPUT_TOO_LARGE",
                expectFailure(runner(stderrProperties, "large-stderr"), "ignored").errorCode());
    }

    @Test
    void terminatesTimedOutProcess() {
        PythonAnalysisProperties properties = properties(javaExecutable().toString());
        properties.setTimeoutSeconds(1);
        PythonAnalysisRunner runner = runner(properties, "timeout");

        PythonAnalysisRunner.PythonRunnerException error = expectFailure(runner, "ignored");

        assertEquals("PYTHON_TIMEOUT", error.errorCode());
    }

    private PythonAnalysisRunner runner(PythonAnalysisProperties properties, String mode) {
        return new PythonAnalysisRunner(properties, objectMapper) {
            @Override
            protected Process startProcess(Path runnerScript, Path temporaryDirectory)
                    throws java.io.IOException {
                String classpath = absoluteClasspath();
                return new ProcessBuilder(
                        javaExecutable().toString(),
                        "-cp",
                        classpath,
                        FakePythonProcess.class.getName(),
                        mode)
                        .directory(temporaryDirectory.toFile())
                        .start();
            }
        };
    }

    private PythonAnalysisRunner.PythonRunnerException expectFailure(
            PythonAnalysisRunner runner, String code) {
        try {
            runner.run(dataset(), code);
            fail("expected PythonRunnerException");
            return null;
        } catch (PythonAnalysisRunner.PythonRunnerException e) {
            return e;
        }
    }

    private PythonAnalysisProperties properties(String executable) {
        PythonAnalysisProperties properties = new PythonAnalysisProperties();
        properties.setExecutable(executable);
        properties.setTimeoutSeconds(5);
        properties.setMaxInputBytes(4096);
        properties.setMaxOutputBytes(4096);
        properties.setMaxStderrBytes(4096);
        return properties;
    }

    private byte[] dataset() {
        return "{\"columns\":[],\"rows\":[],\"rowCount\":0}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Path javaExecutable() {
        String name = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", name);
    }

    private String absoluteClasspath() {
        String separator = System.getProperty("path.separator");
        return java.util.Arrays.stream(System.getProperty("java.class.path").split(
                        java.util.regex.Pattern.quote(separator)))
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(separator));
    }

    public static class FakePythonProcess {
        public static void main(String[] args) throws Exception {
            if ("dependency-missing".equals(args[0])) {
                System.err.print("ModuleNotFoundError: No module named scipy");
                System.exit(1);
            }
            System.in.readAllBytes();
            switch (args[0]) {
                case "normal" -> System.out.print(
                        "{\"result\":{\"value\":3},\"warnings\":[]}");
                case "multiple-json" -> System.out.print(
                        "{\"result\":{}} {\"result\":{}}");
                case "missing-result" -> System.out.print("{\"warnings\":[]}");
                case "array-result" -> System.out.print("{\"result\":[]}");
                case "code-rejected" -> {
                    System.err.print("PYTHON_CODE_REJECTED: import");
                    System.exit(1);
                }
                case "large-stdout" -> System.out.print("x".repeat(32_000));
                case "large-stderr" -> {
                    System.err.print("x".repeat(32_000));
                    Thread.sleep(10_000);
                }
                case "timeout" -> Thread.sleep(10_000);
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
}
