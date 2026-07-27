package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import java.util.Objects;

/**
 * 保存单次 Agent Run 的显式、非全局状态。
 *
 * <p>该类只负责计数、预算预检和唯一终态，不访问模型、Tool 或 Session。
 * Runtime 通过它保证最后一个允许的模型回合仍可正常完成，并且多 Tool Call
 * 批次在预算不足时整批拒绝。</p>
 *
 * @since 0.1.0
 */
final class AgentRunState {

    private final SessionId sessionId;
    private final RunId runId;
    private final AgentLimits limits;
    private int modelTurns;
    private int toolCalls;
    private boolean finished;

    AgentRunState(SessionId sessionId, RunId runId, AgentLimits limits) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        this.runId = Objects.requireNonNull(runId, "runId 不能为空");
        this.limits = Objects.requireNonNull(limits, "limits 不能为空");
    }

    boolean canRequestModelTurn() {
        return modelTurns < limits.maxModelTurns();
    }

    int recordModelTurnAttempt() {
        ensureRunning();
        if (!canRequestModelTurn()) {
            throw new IllegalStateException("模型回合预算已经耗尽");
        }
        modelTurns++;
        return modelTurns;
    }

    boolean canAcceptToolBatch(int batchSize) {
        if (batchSize < 0) {
            throw new IllegalArgumentException("batchSize 不能小于 0");
        }
        return toolCalls + batchSize <= limits.maxToolCalls();
    }

    int recordToolCall() {
        ensureRunning();
        if (toolCalls >= limits.maxToolCalls()) {
            throw new IllegalStateException("Tool Call 预算已经耗尽");
        }
        toolCalls++;
        return toolCalls;
    }

    AgentRunResult complete(String finalText) {
        markFinished();
        return AgentRunResult.completed(
                sessionId,
                runId,
                finalText,
                modelTurns,
                toolCalls);
    }

    AgentRunResult stop(StopReason reason) {
        markFinished();
        return AgentRunResult.stopped(
                sessionId,
                runId,
                reason,
                modelTurns,
                toolCalls);
    }

    private void markFinished() {
        ensureRunning();
        finished = true;
    }

    private void ensureRunning() {
        if (finished) {
            throw new IllegalStateException("Agent Run 已经进入终态");
        }
    }
}
