package com.example.harness.llm.model;

/** 单次模型响应，保留 assistant 消息、结束原因、模型名称和 Token 使用量。 */
public record LlmResponse(
        LlmMessage assistantMessage,
        String finishReason,
        String model,
        LlmUsage usage) {

    public LlmResponse {
        if (assistantMessage == null) {
            throw new IllegalArgumentException("模型响应消息不能为空");
        }
        usage = usage == null ? LlmUsage.unknown() : usage;
    }

    /** 兼容测试和自定义客户端的最小响应构造方式。 */
    public LlmResponse(LlmMessage assistantMessage, String finishReason) {
        this(assistantMessage, finishReason, null, LlmUsage.unknown());
    }
}
