package com.example.harness.agent;

import com.example.harness.llm.LlmClient;
import com.example.harness.llm.model.LlmMessage;
import com.example.harness.llm.model.LlmRequest;
import com.example.harness.llm.model.LlmResponse;
import com.example.harness.llm.model.LlmToolCall;
import com.example.harness.context.ContextManager;
import com.example.harness.task.TaskState;
import com.example.harness.tool.ToolExecutor;
import com.example.harness.tool.ToolRegistry;
import com.example.harness.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;

@Component
public class AgentLoop {

    private static final int MAX_MODEL_REQUESTS = 10;
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final LlmClient llmClient;
    private final ToolExecutor toolExecutor;
    private final ToolRegistry toolRegistry;
    private final ContextManager contextManager;

    /** 由 Spring 注入模型客户端、工具执行器和注册表。 */
    public AgentLoop(
            LlmClient llmClient,
            ToolExecutor toolExecutor,
            ToolRegistry toolRegistry,
            ContextManager contextManager) {
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.toolRegistry = toolRegistry;
        this.contextManager = contextManager;
    }

    /**
     * 维护单次请求的完整消息历史，最多请求模型十次。
     * 有工具调用时保存 assistant 消息、执行全部工具并追加对应结果；否则返回最终正文。
     */
    public String run(TaskState task, String userMessage) {
        contextManager.appendUserMessage(task, userMessage);
        var toolDefinitions = toolRegistry.listDefinitions();

        try {
            for (int round = 0; round < MAX_MODEL_REQUESTS; round++) {
                int llmCallNo = round + 1;
                MDC.put("llmCallNo", String.valueOf(llmCallNo));
                contextManager.recordModelRequest(task);
                long llmStartedAt = System.nanoTime();
                log.info("大模型调用开始 callNo={} modelRequestCount={}",
                        llmCallNo, task.modelRequestCount());
                LlmResponse response;
                try {
                    response = llmClient.complete(new LlmRequest(
                            contextManager.buildMessages(task),
                            toolDefinitions));
                } catch (RuntimeException e) {
                    log.error("大模型调用失败 callNo={} elapsedMs={} exceptionType={}",
                            llmCallNo, elapsedMillis(llmStartedAt), e.getClass().getSimpleName());
                    throw e;
                }
                LlmMessage assistantMessage = response.assistantMessage();
                List<LlmToolCall> toolCalls = assistantMessage.toolCalls();
                int contentLength = assistantMessage.content() == null ? 0 : assistantMessage.content().length();
                log.info("大模型调用完成 callNo={} elapsedMs={} toolCallCount={} contentLength={}",
                        llmCallNo, elapsedMillis(llmStartedAt), toolCalls.size(), contentLength);

                if (toolCalls.isEmpty()) {
                    log.info("Agent 决策 action=返回最终答案 callNo={}", llmCallNo);
                    if (assistantMessage.content() == null || assistantMessage.content().isBlank()) {
                        throw new IllegalStateException("千问 API 没有返回有效回答");
                    }
                    contextManager.appendFinalAnswer(task, assistantMessage);
                    return assistantMessage.content();
                }

                log.info("Agent 决策 action=执行工具 callNo={} toolCallCount={}",
                        llmCallNo, toolCalls.size());
                List<LlmMessage> toolMessages = new ArrayList<>();
                for (LlmToolCall toolCall : toolCalls) {
                    ToolResult result = toolExecutor.execute(
                            toolCall.id(),
                            toolCall.name(),
                            toolCall.arguments());
                    toolMessages.add(LlmMessage.tool(toolCall.id(), result.toModelContent()));
                }
                contextManager.appendToolExchange(task, assistantMessage, toolMessages);
            }
        } finally {
            MDC.remove("llmCallNo");
        }

        throw new IllegalStateException("Agent 超过最大循环次数 10 轮");
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}

