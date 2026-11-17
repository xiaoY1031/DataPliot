package com.example.harness.llm.model;

/** 单次模型调用的 Token 使用量；供应商未返回时使用 -1 表示未知。 */
public record LlmUsage(
        long promptTokens,
        long completionTokens,
        long totalTokens) {

    private static final long UNKNOWN = -1;

    public LlmUsage {
        if (promptTokens < UNKNOWN || completionTokens < UNKNOWN || totalTokens < UNKNOWN) {
            throw new IllegalArgumentException("Token 使用量不能小于 -1");
        }
        if (totalTokens == UNKNOWN && promptTokens >= 0 && completionTokens >= 0) {
            totalTokens = promptTokens + completionTokens;
        }
    }

    public static LlmUsage unknown() {
        return new LlmUsage(UNKNOWN, UNKNOWN, UNKNOWN);
    }

    public boolean known() {
        return promptTokens >= 0 || completionTokens >= 0 || totalTokens >= 0;
    }
}
