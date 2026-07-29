package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelUsage;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 单个模型尝试的隐私安全观测结果。
 *
 * <p>该投影有意不包含请求消息、响应文本、Provider Endpoint 或模型名。
 * 未完成尝试没有 Finish Reason 与 Usage，但仍保留截至 Run 结束的耗时。</p>
 *
 * @param turnNumber Run 内从 1 开始的模型回合序号
 * @param elapsed 开始边界到完成边界或 Run 结束边界的耗时
 * @param completed 是否收到规范的 Model Turn 完成事件
 * @param finishReason 完成时的 Provider-neutral 结束原因
 * @param usage Provider 明确返回时的单回合 Token Usage
 * @since 0.1.0
 */
public record ModelTurnTelemetry(
        int turnNumber,
        Duration elapsed,
        boolean completed,
        Optional<ModelFinishReason> finishReason,
        Optional<ModelUsage> usage) {

    /**
     * 校验回合序号、耗时和完成状态的一致性。
     */
    public ModelTurnTelemetry {
        if (turnNumber < 1) {
            throw new IllegalArgumentException("turnNumber 必须从 1 开始");
        }
        elapsed = requireNonNegative(elapsed);
        finishReason = Objects.requireNonNull(finishReason, "finishReason 不能为空");
        usage = Objects.requireNonNull(usage, "usage 不能为空");
        if (completed != finishReason.isPresent()) {
            throw new IllegalArgumentException("完成状态与 finishReason 不一致");
        }
        if (!completed && usage.isPresent()) {
            throw new IllegalArgumentException("未完成回合不能包含 Usage");
        }
    }

    private static Duration requireNonNegative(Duration value) {
        Objects.requireNonNull(value, "elapsed 不能为空");
        if (value.isNegative()) {
            throw new IllegalArgumentException("elapsed 不能为负数");
        }
        return value;
    }
}
