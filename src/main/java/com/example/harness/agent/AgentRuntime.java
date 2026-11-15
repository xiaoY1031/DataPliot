package com.example.harness.agent;

import com.example.harness.task.TaskState;
import com.example.harness.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** 对外提供稳定的 Agent 执行入口，当前委托统一的 AgentLoop。 */
@Component
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private final AgentLoop agentLoop;
    private final TaskStore taskStore;

    public AgentRuntime(AgentLoop agentLoop, TaskStore taskStore) {
        this.agentLoop = agentLoop;
        this.taskStore = taskStore;
    }

    /** 不传 taskId 时创建新任务；传入时继续已有任务。 */
    public AgentResult run(String requestedTaskId, String userMessage) {
        long startedAt = System.nanoTime();
        String taskId = "unresolved";
        try {
            TaskState task = requestedTaskId == null || requestedTaskId.isBlank()
                    ? taskStore.create(userMessage)
                    : taskStore.require(requestedTaskId);
            taskId = task.taskId();
            MDC.put("taskId", taskId);
            log.info("Turn 开始 taskId={} goalLength={}", taskId, task.goal().length());

            AgentResult result = taskStore.withTaskLock(taskId, lockedTask -> {
                taskStore.start(lockedTask);
                try {
                    String answer = agentLoop.run(lockedTask, userMessage);
                    taskStore.complete(lockedTask);
                    log.info("Agent 返回最终答案 taskId={} answerLength={}",
                            lockedTask.taskId(), answer.length());
                    return new AgentResult(lockedTask.taskId(), answer);
                } catch (RuntimeException e) {
                    taskStore.fail(lockedTask, e.getMessage());
                    throw e;
                }
            });
            log.info("Turn 正常结束 taskId={} elapsedMs={}", taskId, elapsedMillis(startedAt));
            return result;
        } catch (RuntimeException e) {
            if (e instanceof TaskStore.UnknownTaskException) {
                log.warn("Turn 异常结束 taskId={} elapsedMs={} exceptionType={}",
                        taskId, elapsedMillis(startedAt), e.getClass().getSimpleName());
            } else {
                log.error("Turn 异常结束 taskId={} elapsedMs={} exceptionType={}",
                        taskId, elapsedMillis(startedAt), e.getClass().getSimpleName());
            }
            throw e;
        } finally {
            MDC.remove("taskId");
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    public record AgentResult(String taskId, String answer) {
    }
}
