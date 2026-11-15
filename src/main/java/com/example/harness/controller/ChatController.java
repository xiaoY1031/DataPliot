package com.example.harness.controller;

import com.example.harness.agent.AgentRuntime;
import com.example.harness.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentRuntime agentRuntime;

    /** 由 Spring 注入 Agent Runtime，控制器不再依赖具体模型客户端。 */
    public ChatController(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    /** 接收 message，执行工具并将结果交回模型，将最终回答封装为 answer 返回。 */
    @PostMapping
    public ResponseEntity<Map<String, String>> chat(
            @RequestHeader(value = "X-Task-Id", required = false) String taskId,
            @RequestBody ChatRequest request) {
        String turnId = UUID.randomUUID().toString();
        MDC.put("turnId", turnId);
        int messageLength = request == null || request.message() == null ? 0 : request.message().length();
        log.info("收到聊天请求 messageLength={}", messageLength);
        try {
            AgentRuntime.AgentResult result = agentRuntime.run(taskId, request.message());
            log.info("聊天响应已返回 taskId={} answerLength={}",
                    result.taskId(), result.answer().length());
            return ResponseEntity.ok()
                    .header("X-Task-Id", result.taskId())
                    .header("X-Turn-Id", turnId)
                    .body(Map.of("answer", result.answer()));
        } catch (TaskStore.UnknownTaskException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } finally {
            MDC.remove("turnId");
        }
    }

    public record ChatRequest(String message) {
    }
}
