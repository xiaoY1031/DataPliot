package com.example.harness.agent;

import com.example.harness.llm.LlmClient;
import com.example.harness.llm.model.LlmMessage;
import com.example.harness.llm.model.LlmRequest;
import com.example.harness.llm.model.LlmResponse;
import com.example.harness.llm.model.LlmToolCall;
import com.example.harness.llm.model.LlmUsage;
import com.example.harness.context.ContextManager;
import com.example.harness.task.TaskState;
import com.example.harness.tool.ToolDefinition;
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

    private static final int NORMAL_MODEL_REQUESTS = 10;
    private static final int FINALIZATION_MODEL_REQUESTS = 5;
    private static final int MAX_MODEL_REQUESTS =
            NORMAL_MODEL_REQUESTS + FINALIZATION_MODEL_REQUESTS;
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
     * 维护单次请求的完整消息历史。正常阶段最多请求模型十次；仍未完成时进入一次性五次收尾阶段，
     * 最后一次关闭工具并要求模型基于已有证据返回最终正文。
     */
    public String run(TaskState task, String userMessage) {
        contextManager.appendUserMessage(task, userMessage);
        var toolDefinitions = toolRegistry.listDefinitions();

        try {
            for (int round = 0; round < MAX_MODEL_REQUESTS; round++) {
                int llmCallNo = round + 1;
                boolean finalizationPhase = llmCallNo > NORMAL_MODEL_REQUESTS;
                boolean forcedFinalization = llmCallNo == MAX_MODEL_REQUESTS;
                if (llmCallNo == NORMAL_MODEL_REQUESTS + 1) {
                    log.warn("Agent 进入收尾阶段 extensionGranted=true normalLimit={} "
                                    + "finalizationLimit={}",
                            NORMAL_MODEL_REQUESTS, FINALIZATION_MODEL_REQUESTS);
                }
                MDC.put("llmCallNo", String.valueOf(llmCallNo));
                contextManager.recordModelRequest(task);
                List<LlmMessage> messages = buildRequestMessages(
                        task, llmCallNo, finalizationPhase, forcedFinalization);
                List<ToolDefinition> availableTools = forcedFinalization
                        ? List.of()
                        : toolDefinitions;
                long llmStartedAt = System.nanoTime();
                log.info("大模型调用开始 callNo={} phase={} remainingCalls={} modelRequestCount={} "
                                + "messageCount={} availableToolCount={}",
                        llmCallNo,
                        finalizationPhase ? "FINALIZATION" : "NORMAL",
                        finalizationPhase
                                ? MAX_MODEL_REQUESTS - llmCallNo + 1
                                : NORMAL_MODEL_REQUESTS - llmCallNo + 1,
                        task.modelRequestCount(),
                        messages.size(),
                        availableTools.size());
                LlmResponse response;
                try {
                    response = llmClient.complete(new LlmRequest(
                            messages,
                            availableTools));
                } catch (RuntimeException e) {
                    log.error("大模型调用失败 callNo={} elapsedMs={} exceptionType={}",
                            llmCallNo, elapsedMillis(llmStartedAt), e.getClass().getSimpleName());
                    throw e;
                }
                contextManager.recordModelUsage(task, response.usage());
                LlmMessage assistantMessage = response.assistantMessage();
                List<LlmToolCall> toolCalls = assistantMessage.toolCalls();
                int contentLength = assistantMessage.content() == null ? 0 : assistantMessage.content().length();
                LlmUsage usage = response.usage();
                log.info("大模型调用完成 callNo={} elapsedMs={} model={} finishReason={} "
                                + "toolCallCount={} contentLength={} promptTokens={} completionTokens={} totalTokens={}",
                        llmCallNo, elapsedMillis(llmStartedAt), response.model(), response.finishReason(),
                        toolCalls.size(), contentLength, usage.promptTokens(),
                        usage.completionTokens(), usage.totalTokens());

                if (forcedFinalization && !toolCalls.isEmpty()) {
                    log.error("Agent 强制收尾失败 callNo={} unexpectedToolCallCount={}",
                            llmCallNo, toolCalls.size());
                    throw new IllegalStateException("模型在最终收尾调用中仍请求工具，未能生成最终答案");
                }

                if (toolCalls.isEmpty()) {
                    log.info("Agent 决策 action=返回最终答案 callNo={}", llmCallNo);
                    if (assistantMessage.content() == null || assistantMessage.content().isBlank()) {
                        throw new IllegalStateException("模型 API 没有返回有效回答");
                    }
                    contextManager.appendFinalAnswer(task, assistantMessage);
                    return assistantMessage.content();
                }

                log.info("Agent 决策 action=执行工具 callNo={} toolCallCount={}",
                        llmCallNo, toolCalls.size());
                List<LlmMessage> toolMessages = new ArrayList<>();
                int toolFailureCount = 0;
                for (LlmToolCall toolCall : toolCalls) {
                    ToolResult result = toolExecutor.execute(
                            toolCall.id(),
                            toolCall.name(),
                            toolCall.arguments());
                    if (!result.success()) {
                        toolFailureCount++;
                    }
                    toolMessages.add(LlmMessage.tool(toolCall.id(), result.toModelContent()));
                }
                contextManager.appendToolExchange(task, assistantMessage, toolMessages);
                contextManager.recordToolFailures(task, toolFailureCount);
            }
        } finally {
            MDC.remove("llmCallNo");
        }

        throw new IllegalStateException("Agent 超过最大循环次数 15 轮");
    }

    private List<LlmMessage> buildRequestMessages(
            TaskState task,
            int llmCallNo,
            boolean finalizationPhase,
            boolean forcedFinalization) {
        List<LlmMessage> messages = new ArrayList<>(contextManager.buildMessages(task));
        if (!finalizationPhase) {
            return List.copyOf(messages);
        }

        int remainingCalls = MAX_MODEL_REQUESTS - llmCallNo + 1;
        String instruction = forcedFinalization
                ? "你已到达本轮任务最后一次模型调用。不得调用任何工具。必须立即基于已有对话和 Tool Result "
                        + "生成最终答案；证据不足时明确说明限制，不得编造数据。"
                : "你已进入一次性收尾阶段，本次调用在内最多还剩 " + remainingCalls
                        + " 次模型调用。优先复用已有 Tool Result，只在缺失信息会直接影响核心结论时继续调用工具，"
                        + "并务必在剩余次数内生成最终答案，不要扩展无关分析。";
        int insertionIndex = 0;
        while (insertionIndex < messages.size()
                && messages.get(insertionIndex).role() == LlmMessage.Role.SYSTEM) {
            insertionIndex++;
        }
        messages.add(insertionIndex, LlmMessage.system(instruction));
        return List.copyOf(messages);
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}

