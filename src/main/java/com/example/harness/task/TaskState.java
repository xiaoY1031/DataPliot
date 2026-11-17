package com.example.harness.task;

import com.example.harness.llm.model.LlmMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 内存任务状态；所有可变数据都由 TaskStore 在任务锁内更新。 */
public class TaskState {

    private final String taskId;
    private final String goal;
    private Status status;
    private final List<LlmMessage> conversationHistory = new ArrayList<>();
    private int turnCount;
    private int modelRequestCount;
    private int toolCallCount;
    private int toolFailureCount;
    private long promptTokenCount;
    private long completionTokenCount;
    private long totalTokenCount;
    private int modelUsageReportCount;
    private String lastError;
    private final Instant createdAt;
    private Instant updatedAt;

    TaskState(String taskId, String goal) {
        this.taskId = taskId;
        this.goal = goal;
        this.status = Status.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public String taskId() {
        return taskId;
    }

    public String goal() {
        return goal;
    }

    public synchronized Status status() {
        return status;
    }

    public synchronized List<LlmMessage> conversationHistory() {
        return List.copyOf(conversationHistory);
    }

    public synchronized int turnCount() {
        return turnCount;
    }

    public synchronized int modelRequestCount() {
        return modelRequestCount;
    }

    public synchronized int toolCallCount() {
        return toolCallCount;
    }

    public synchronized int toolFailureCount() {
        return toolFailureCount;
    }

    public synchronized long promptTokenCount() {
        return promptTokenCount;
    }

    public synchronized long completionTokenCount() {
        return completionTokenCount;
    }

    public synchronized long totalTokenCount() {
        return totalTokenCount;
    }

    public synchronized int modelUsageReportCount() {
        return modelUsageReportCount;
    }

    public synchronized String lastError() {
        return lastError;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public synchronized Instant updatedAt() {
        return updatedAt;
    }

    synchronized void start() {
        status = Status.RUNNING;
        lastError = null;
        touch();
    }

    synchronized void complete() {
        status = Status.COMPLETED;
        touch();
    }

    synchronized void fail(String error) {
        status = Status.FAILED;
        lastError = error;
        touch();
    }

    synchronized void append(LlmMessage message) {
        conversationHistory.add(message);
        touch();
    }

    synchronized void appendAll(List<LlmMessage> messages) {
        conversationHistory.addAll(messages);
        touch();
    }

    synchronized void incrementTurnCount() {
        turnCount++;
        touch();
    }

    synchronized void incrementModelRequestCount() {
        modelRequestCount++;
        touch();
    }

    synchronized void addToolCalls(int count) {
        toolCallCount += count;
        touch();
    }

    synchronized void addToolFailures(int count) {
        toolFailureCount += count;
        touch();
    }

    synchronized void addModelUsage(long promptTokens, long completionTokens, long totalTokens) {
        modelUsageReportCount++;
        if (promptTokens >= 0) {
            promptTokenCount += promptTokens;
        }
        if (completionTokens >= 0) {
            completionTokenCount += completionTokens;
        }
        if (totalTokens >= 0) {
            totalTokenCount += totalTokens;
        }
        touch();
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    public enum Status {
        CREATED,
        RUNNING,
        COMPLETED,
        FAILED
    }
}

