package com.example.harness.tool;

/** 工具内部使用的结构化结果，返回模型前会转换为文本。 */
public record ToolResult(
        boolean success,
        String content,
        String errorCode) {

    public ToolResult {
        content = content == null ? "" : content;
        if (success) {
            errorCode = null;
        } else if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("失败的工具结果必须包含错误码");
        }
    }

    public static ToolResult success(String content) {
        return new ToolResult(true, content, null);
    }

    public static ToolResult failure(String errorCode, String message) {
        return new ToolResult(false, message, errorCode);
    }

    /** 转换为 OpenAI 兼容协议使用的工具消息文本。 */
    public String toModelContent() {
        return success ? content : "工具错误[" + errorCode + "]：" + content;
    }
}
