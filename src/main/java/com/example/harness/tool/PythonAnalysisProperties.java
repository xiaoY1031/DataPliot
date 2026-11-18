package com.example.harness.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 本地可信开发环境中的受限 Python 分析执行器配置。 */
@Component
@ConfigurationProperties(prefix = "datapilot.python")
public class PythonAnalysisProperties {

    private boolean enabled;
    private String executable = "";
    private int timeoutSeconds = 15;
    private int queryTimeoutSeconds = 10;
    private int maxCodeLength = 20_000;
    private int maxInputRows = 10_000;
    private int maxInputBytes = 5_242_880;
    private int maxOutputBytes = 262_144;
    private int maxStderrBytes = 65_536;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        this.executable = executable == null ? "" : executable.trim();
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public int getMaxCodeLength() {
        return maxCodeLength;
    }

    public void setMaxCodeLength(int maxCodeLength) {
        this.maxCodeLength = maxCodeLength;
    }

    public int getMaxInputRows() {
        return maxInputRows;
    }

    public void setMaxInputRows(int maxInputRows) {
        this.maxInputRows = maxInputRows;
    }

    public int getMaxInputBytes() {
        return maxInputBytes;
    }

    public void setMaxInputBytes(int maxInputBytes) {
        this.maxInputBytes = maxInputBytes;
    }

    public int getMaxOutputBytes() {
        return maxOutputBytes;
    }

    public void setMaxOutputBytes(int maxOutputBytes) {
        this.maxOutputBytes = maxOutputBytes;
    }

    public int getMaxStderrBytes() {
        return maxStderrBytes;
    }

    public void setMaxStderrBytes(int maxStderrBytes) {
        this.maxStderrBytes = maxStderrBytes;
    }
}
