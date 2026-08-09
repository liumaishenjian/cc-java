package io.github.liumaishenjian.ccjava.domain.subagent;

import java.time.Duration;
import java.util.Objects;

/**
 * 父任务可原子预留给子任务的多维预算。
 *
 * @param modelTurns 最大模型回合
 * @param toolCalls 最大 Tool 调用
 * @param inputTokens 最大估算输入 Token
 * @param outputCharacters 最大输出字符
 * @param duration 最大墙钟时间
 * @since 0.12.0
 */
public record ChildBudget(int modelTurns, int toolCalls, long inputTokens,
                          int outputCharacters, Duration duration) {
    public ChildBudget {
        duration = Objects.requireNonNull(duration, "duration 不能为空");
        if (modelTurns < 1 || toolCalls < 0 || inputTokens < 1 || outputCharacters < 1
                || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("子任务预算必须为正值");
        }
    }
}
