package com.example.harness.tool;

import java.util.Map;

/** 单次 Python 子进程的资源、超时和输出约束。 */
public record PythonProcessSpec(
        String scriptResource,
        String scriptFileName,
        String temporaryDirectoryPrefix,
        int timeoutSeconds,
        int maxStdinBytes,
        int maxStdoutBytes,
        int maxStderrBytes,
        Map<String, Integer> outputFiles) {

    public PythonProcessSpec {
        outputFiles = outputFiles == null ? Map.of() : Map.copyOf(outputFiles);
    }
}
