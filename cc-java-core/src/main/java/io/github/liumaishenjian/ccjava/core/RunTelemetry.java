package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 单次 Run 的不可变、隐私安全观测快照。
 *
 * <p>总 Token 只有在所有已完成模型回合都存在 Provider Usage 时才出现。
 * 这避免把部分统计误读为完整统计，也避免用零值掩盖未知值。</p>
 *
 * @param sessionId 所属 Session
 * @param runId 所属 Run
 * @param elapsed Run 开始到结束的耗时
 * @param modelTurns 模型尝试的边界耗时
 * @param toolCalls Tool Call 的边界耗时
 * @param usageReportedTurns 明确包含 Provider Usage 的完成回合数
 * @param usageMissingTurns 未包含 Provider Usage 的完成回合数
 * @param totalUsage 完整覆盖全部完成回合时的 Token 总和
 * @since 0.1.0
 */
public record RunTelemetry(
        SessionId sessionId,
        RunId runId,
        Duration elapsed,
        List<ModelTurnTelemetry> modelTurns,
        List<ToolCallTelemetry> toolCalls,
        int usageReportedTurns,
        int usageMissingTurns,
        Optional<TokenUsageTotals> totalUsage) {

    /**
     * 复制集合并校验 Usage 覆盖语义。
     */
    public RunTelemetry {
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        runId = Objects.requireNonNull(runId, "runId 不能为空");
        elapsed = Objects.requireNonNull(elapsed, "elapsed 不能为空");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed 不能为负数");
        }
        modelTurns = List.copyOf(Objects.requireNonNull(modelTurns, "modelTurns 不能为空"));
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls 不能为空"));
        totalUsage = Objects.requireNonNull(totalUsage, "totalUsage 不能为空");
        if (usageReportedTurns < 0 || usageMissingTurns < 0) {
            throw new IllegalArgumentException("Usage 回合数不能为负数");
        }
        long completedTurns = modelTurns.stream()
                .filter(ModelTurnTelemetry::completed)
                .count();
        if (completedTurns != (long) usageReportedTurns + usageMissingTurns) {
            throw new IllegalArgumentException("Usage 覆盖数与已完成模型回合数不一致");
        }
        boolean completeCoverage = usageReportedTurns > 0 && usageMissingTurns == 0;
        if (totalUsage.isPresent() != completeCoverage) {
            throw new IllegalArgumentException("totalUsage 与 Usage 覆盖状态不一致");
        }
    }
}
