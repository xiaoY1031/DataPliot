package com.example.harness.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 固定协议图表生成器的查询、渲染和 artifact 限制。 */
@Component
@ConfigurationProperties(prefix = "datapilot.chart")
public class ChartGenerationProperties {

    private boolean enabled;
    private String outputDirectory = "";
    private int timeoutSeconds = 15;
    private int queryTimeoutSeconds = 10;
    private int maxInputRows = 5_000;
    private int maxInputBytes = 5_242_880;
    private int maxStdoutBytes = 65_536;
    private int maxStderrBytes = 65_536;
    private int maxImageBytes = 2_097_152;
    private int maxWidth = 2_400;
    private int maxHeight = 1_600;
    private int retentionHours = 24;
    private int maxArtifacts = 200;
    private int maxCategories = 50;
    private int maxSeries = 12;
    private int maxPoints = 2_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getOutputDirectory() { return outputDirectory; }
    public void setOutputDirectory(String value) { outputDirectory = value == null ? "" : value.trim(); }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int value) { timeoutSeconds = value; }
    public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
    public void setQueryTimeoutSeconds(int value) { queryTimeoutSeconds = value; }
    public int getMaxInputRows() { return maxInputRows; }
    public void setMaxInputRows(int value) { maxInputRows = value; }
    public int getMaxInputBytes() { return maxInputBytes; }
    public void setMaxInputBytes(int value) { maxInputBytes = value; }
    public int getMaxStdoutBytes() { return maxStdoutBytes; }
    public void setMaxStdoutBytes(int value) { maxStdoutBytes = value; }
    public int getMaxStderrBytes() { return maxStderrBytes; }
    public void setMaxStderrBytes(int value) { maxStderrBytes = value; }
    public int getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(int value) { maxImageBytes = value; }
    public int getMaxWidth() { return maxWidth; }
    public void setMaxWidth(int value) { maxWidth = value; }
    public int getMaxHeight() { return maxHeight; }
    public void setMaxHeight(int value) { maxHeight = value; }
    public int getRetentionHours() { return retentionHours; }
    public void setRetentionHours(int value) { retentionHours = value; }
    public int getMaxArtifacts() { return maxArtifacts; }
    public void setMaxArtifacts(int value) { maxArtifacts = value; }
    public int getMaxCategories() { return maxCategories; }
    public void setMaxCategories(int value) { maxCategories = value; }
    public int getMaxSeries() { return maxSeries; }
    public void setMaxSeries(int value) { maxSeries = value; }
    public int getMaxPoints() { return maxPoints; }
    public void setMaxPoints(int value) { maxPoints = value; }
}
