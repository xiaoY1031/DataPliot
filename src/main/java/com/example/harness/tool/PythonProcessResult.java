package com.example.harness.tool;

import java.util.HashMap;
import java.util.Map;

/** Python 子进程的原始受限输出；业务 Runner 负责解释具体协议。 */
public record PythonProcessResult(
        int exitCode,
        byte[] stdout,
        byte[] stderr,
        Map<String, byte[]> outputFiles,
        long processMs) {

    public PythonProcessResult {
        stdout = stdout == null ? new byte[0] : stdout.clone();
        stderr = stderr == null ? new byte[0] : stderr.clone();
        Map<String, byte[]> copied = new HashMap<>();
        if (outputFiles != null) {
            outputFiles.forEach((name, value) -> copied.put(name, value.clone()));
        }
        outputFiles = Map.copyOf(copied);
    }

    @Override
    public byte[] stdout() {
        return stdout.clone();
    }

    @Override
    public byte[] stderr() {
        return stderr.clone();
    }

    @Override
    public Map<String, byte[]> outputFiles() {
        Map<String, byte[]> copied = new HashMap<>();
        outputFiles.forEach((name, value) -> copied.put(name, value.clone()));
        return Map.copyOf(copied);
    }
}
