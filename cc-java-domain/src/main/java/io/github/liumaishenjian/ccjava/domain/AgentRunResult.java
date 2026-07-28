package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 单次 Agent Run 的不可变终态摘要。
 *
 * @param sessionId   所属 Session
 * @param runId       本次 Run
 * @param status      高层完成状态
 * @param stopReason  精确终止原因
 * @param finalText   正常完成时的最终 Assistant 文本
 * @param modelTurns  已实际请求的模型回合数
 * @param toolCalls   已交给 Pipeline 处理的 Tool Call 数
 * @param usage       每个已请求逻辑回合都返回 Usage 时的精确聚合值
 * @since 0.1.0
 */
public record AgentRunResult(
        SessionId sessionId,
        RunId runId,
        RunStatus status,
        StopReason stopReason,
        Optional<String> finalText,
        int modelTurns,
        int toolCalls,
        Optional<ModelUsage> usage) {

    /**
     * 创建不包含 Usage 的兼容 S01 结果。
     *
     * @param sessionId 所属 Session
     * @param runId 本次 Run
     * @param status 高层完成状态
     * @param stopReason 精确终止原因
     * @param finalText 正常完成时的最终文本
     * @param modelTurns 已请求的逻辑模型回合数
     * @param toolCalls 已处理的 Tool Call 数
     */
    public AgentRunResult(
            SessionId sessionId,
            RunId runId,
            RunStatus status,
            StopReason stopReason,
            Optional<String> finalText,
            int modelTurns,
            int toolCalls) {
        this(
                sessionId,
                runId,
                status,
                stopReason,
                finalText,
                modelTurns,
                toolCalls,
                Optional.empty());
    }

    /**
     * 校验终态不变量后创建 Run 结果。
     *
     * @param sessionId 所属 Session
     * @param runId 本次 Run
     * @param status 高层完成状态
     * @param stopReason 精确终止原因
     * @param finalText 正常完成时的最终文本
     * @param modelTurns 已请求的模型回合数
     * @param toolCalls 已处理的 Tool Call 数
     * @param usage 每个已请求逻辑回合都有可信 Usage 时的聚合值
     * @throws NullPointerException 必填引用或 Optional 容器为空时
     * @throws IllegalArgumentException 计数、状态或最终文本与终止原因不一致时
     */
    public AgentRunResult {
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        runId = Objects.requireNonNull(runId, "runId 不能为空");
        status = Objects.requireNonNull(status, "status 不能为空");
        stopReason = Objects.requireNonNull(stopReason, "stopReason 不能为空");
        finalText = Objects.requireNonNull(finalText, "finalText 不能为空");
        usage = Objects.requireNonNull(usage, "usage 不能为空");
        if (modelTurns < 0) {
            throw new IllegalArgumentException("modelTurns 不能小于 0");
        }
        if (toolCalls < 0) {
            throw new IllegalArgumentException("toolCalls 不能小于 0");
        }
        RunStatus expectedStatus = switch (stopReason) {
            case COMPLETED -> RunStatus.COMPLETED;
            case USER_CANCELLED -> RunStatus.CANCELLED;
            case MODEL_ERROR, INTERNAL_ERROR -> RunStatus.FAILED;
            default -> RunStatus.STOPPED;
        };
        if (status != expectedStatus) {
            throw new IllegalArgumentException(
                    "status 与 stopReason 不一致，期望 " + expectedStatus);
        }
        if (stopReason == StopReason.COMPLETED && finalText.isEmpty()) {
            throw new IllegalArgumentException("正常完成必须包含 finalText");
        }
        if (finalText.isPresent() && finalText.orElseThrow().isBlank()) {
            throw new IllegalArgumentException("finalText 不能为空白");
        }
        if (stopReason != StopReason.COMPLETED && finalText.isPresent()) {
            throw new IllegalArgumentException("非正常完成不能包含 finalText");
        }
    }

    /**
     * 创建正常完成结果。
     *
     * @param sessionId  Session ID
     * @param runId      Run ID
     * @param finalText  最终文本
     * @param modelTurns 模型回合数
     * @param toolCalls  Tool Call 数
     * @return 正常完成摘要
     */
    public static AgentRunResult completed(
            SessionId sessionId,
            RunId runId,
            String finalText,
            int modelTurns,
            int toolCalls) {
        return completed(
                sessionId,
                runId,
                finalText,
                modelTurns,
                toolCalls,
                Optional.empty());
    }

    /**
     * 创建带有可信聚合 Usage 的正常完成结果。
     *
     * @param sessionId Session ID
     * @param runId Run ID
     * @param finalText 最终文本
     * @param modelTurns 模型回合数
     * @param toolCalls Tool Call 数
     * @param usage 完整 Provider Usage；任一回合缺失时为空
     * @return 正常完成摘要
     */
    public static AgentRunResult completed(
            SessionId sessionId,
            RunId runId,
            String finalText,
            int modelTurns,
            int toolCalls,
            Optional<ModelUsage> usage) {
        return new AgentRunResult(
                sessionId,
                runId,
                RunStatus.COMPLETED,
                StopReason.COMPLETED,
                Optional.of(Objects.requireNonNull(finalText, "finalText 不能为空")),
                modelTurns,
                toolCalls,
                Objects.requireNonNull(usage, "usage 不能为空"));
    }

    /**
     * 创建没有最终文本的停止结果。
     *
     * @param sessionId  Session ID
     * @param runId      Run ID
     * @param reason     非 {@code COMPLETED} 的停止原因
     * @param modelTurns 模型回合数
     * @param toolCalls  Tool Call 数
     * @return 终止摘要
     */
    public static AgentRunResult stopped(
            SessionId sessionId,
            RunId runId,
            StopReason reason,
            int modelTurns,
            int toolCalls) {
        return stopped(
                sessionId,
                runId,
                reason,
                modelTurns,
                toolCalls,
                Optional.empty());
    }

    /**
     * 创建带有可信聚合 Usage、但没有最终文本的停止结果。
     *
     * @param sessionId Session ID
     * @param runId Run ID
     * @param reason 非 {@code COMPLETED} 的停止原因
     * @param modelTurns 模型回合数
     * @param toolCalls Tool Call 数
     * @param usage 完整 Provider Usage；任一回合缺失时为空
     * @return 终止摘要
     */
    public static AgentRunResult stopped(
            SessionId sessionId,
            RunId runId,
            StopReason reason,
            int modelTurns,
            int toolCalls,
            Optional<ModelUsage> usage) {
        Objects.requireNonNull(reason, "reason 不能为空");
        if (reason == StopReason.COMPLETED) {
            throw new IllegalArgumentException("stopped 不能使用 COMPLETED");
        }
        RunStatus status = switch (reason) {
            case USER_CANCELLED -> RunStatus.CANCELLED;
            case MODEL_ERROR, INTERNAL_ERROR -> RunStatus.FAILED;
            default -> RunStatus.STOPPED;
        };
        return new AgentRunResult(
                sessionId,
                runId,
                status,
                reason,
                Optional.empty(),
                modelTurns,
                toolCalls,
                Objects.requireNonNull(usage, "usage 不能为空"));
    }
}
