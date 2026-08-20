package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentBudgetPolicy;
import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.BudgetGovernanceReason;
import io.github.liumaishenjian.ccjava.domain.ModelFailureSummary;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultStatus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 保存单次 Agent Run 的显式、非全局预算状态。
 *
 * <p>显式预算严格不可续租；adaptive 预算只在已完成批次含成功 Tool Result 时扩展下一窗口。
 * 连续失败不构成进展，绝对 ceiling 永远不能扩展。</p>
 *
 * @since 0.1.0
 */
final class AgentRunState {
    private static final int MODEL_EXTENSION = 8;
    private static final int TOOL_EXTENSION = 16;
    private final SessionId sessionId;
    private final RunId runId;
    private final AgentLimits limits;
    private int modelTurns;
    private int toolCalls;
    private int effectiveModelLimit;
    private int effectiveToolLimit;
    private boolean modelProgressSinceGovernance;
    private boolean toolProgressSinceGovernance;
    private boolean finished;

    AgentRunState(SessionId sessionId, RunId runId, AgentLimits limits) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        this.runId = Objects.requireNonNull(runId, "runId 不能为空");
        this.limits = Objects.requireNonNull(limits, "limits 不能为空");
        effectiveModelLimit = limits.maxModelTurns();
        effectiveToolLimit = limits.maxToolCalls();
    }

    Optional<BudgetGovernanceReason> ensureModelBudget() {
        if (modelTurns < effectiveModelLimit) return Optional.empty();
        return extendOrReason(true);
    }

    int recordModelTurnAttempt() {
        ensureRunning();
        if (modelTurns >= effectiveModelLimit) throw new IllegalStateException("模型回合预算已经耗尽");
        return ++modelTurns;
    }

    Optional<BudgetGovernanceReason> ensureToolBatchBudget(int batchSize) {
        if (batchSize < 0) throw new IllegalArgumentException("batchSize 不能小于 0");
        if (toolCalls + batchSize <= effectiveToolLimit) return Optional.empty();
        Optional<BudgetGovernanceReason> reason = extendOrReason(false);
        return toolCalls + batchSize <= effectiveToolLimit
                ? Optional.of(BudgetGovernanceReason.PROGRESS_EXTENDED) : reason;
    }

    int recordToolCall() {
        ensureRunning();
        if (toolCalls >= effectiveToolLimit) throw new IllegalStateException("Tool Call 预算已经耗尽");
        return ++toolCalls;
    }

    void recordBatchResults(List<ToolResult> results) {
        Objects.requireNonNull(results, "results 不能为空");
        if (results.stream().anyMatch(result -> result.status() == ToolResultStatus.SUCCESS)) {
            modelProgressSinceGovernance = true;
            toolProgressSinceGovernance = true;
        }
    }

    int modelTurns() { return modelTurns; }
    int toolCalls() { return toolCalls; }
    int effectiveModelLimit() { return effectiveModelLimit; }
    int effectiveToolLimit() { return effectiveToolLimit; }

    private Optional<BudgetGovernanceReason> extendOrReason(boolean model) {
        if (limits.budgetPolicy() == AgentBudgetPolicy.EXPLICIT_HARD) {
            return Optional.of(BudgetGovernanceReason.EXPLICIT_LIMIT);
        }
        int current = model ? effectiveModelLimit : effectiveToolLimit;
        int absolute = model ? limits.absoluteMaxModelTurns() : limits.absoluteMaxToolCalls();
        if (current >= absolute) return Optional.of(BudgetGovernanceReason.ABSOLUTE_LIMIT);
        boolean progress = model ? modelProgressSinceGovernance : toolProgressSinceGovernance;
        if (!progress) return Optional.of(BudgetGovernanceReason.NO_PROGRESS);
        if (model) effectiveModelLimit = Math.min(absolute, current + MODEL_EXTENSION);
        else effectiveToolLimit = Math.min(absolute, current + TOOL_EXTENSION);
        if (model) modelProgressSinceGovernance = false;
        else toolProgressSinceGovernance = false;
        return Optional.of(BudgetGovernanceReason.PROGRESS_EXTENDED);
    }

    AgentRunResult complete(String text) { markFinished(); return AgentRunResult.completed(sessionId, runId, text, modelTurns, toolCalls); }
    AgentRunResult stop(StopReason reason) { return stop(reason, Optional.empty()); }
    AgentRunResult stop(StopReason reason, Optional<ModelFailureSummary> failure) {
        markFinished(); return AgentRunResult.stopped(sessionId, runId, reason, failure, modelTurns, toolCalls);
    }
    private void markFinished() { ensureRunning(); finished = true; }
    private void ensureRunning() { if (finished) throw new IllegalStateException("Agent Run 已经进入终态"); }
}
