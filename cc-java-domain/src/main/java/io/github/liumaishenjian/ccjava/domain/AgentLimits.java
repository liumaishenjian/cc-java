package io.github.liumaishenjian.ccjava.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * 限制单次 Run 可以消耗的模型回合、Tool Call 数量和墙钟时间。
 *
 * <p>模型回合上限至少为 1。Tool 上限可以为 0，用于显式禁止 Tool；
 * 同一模型回合的多个 Tool Call 作为原子批次预检，预算不足时整批不执行。
 * 墙钟限制从 Run 开始事件之后计时，到期后通过同一取消令牌传播给模型和工具适配器。</p>
 *
 * @param maxModelTurns 单次 Run 允许的最大模型回合数
 * @param maxToolCalls  单次 Run 允许的最大 Tool Call 数
 * @param maxDuration 单次 Run 允许的最大墙钟时间
 * @since 0.1.0
 */
public record AgentLimits(
        int maxModelTurns,
        int maxToolCalls,
        Duration maxDuration) {

    /** 保守的 S01 默认限制。 */
    public static final AgentLimits DEFAULT =
            new AgentLimits(16, 32, Duration.ofMinutes(5));

    /**
     * 使用默认五分钟墙钟限制创建兼容的回合/Tool 预算。
     *
     * @param maxModelTurns 单次 Run 允许的最大模型回合数
     * @param maxToolCalls 单次 Run 允许的最大 Tool Call 数
     */
    public AgentLimits(int maxModelTurns, int maxToolCalls) {
        this(maxModelTurns, maxToolCalls, DEFAULT.maxDuration);
    }

    /**
     * 校验预算边界后创建运行限制。
     *
     * @param maxModelTurns 单次 Run 允许的最大模型回合数
     * @param maxToolCalls 单次 Run 允许的最大 Tool Call 数
     * @param maxDuration 单次 Run 允许的最大墙钟时间
     * @throws IllegalArgumentException 模型回合数小于 1、Tool Call 数小于 0，
     *         或墙钟限制不为正数时
     */
    public AgentLimits {
        maxDuration = Objects.requireNonNull(maxDuration, "maxDuration 不能为空");
        if (maxModelTurns < 1) {
            throw new IllegalArgumentException("maxModelTurns 必须大于 0");
        }
        if (maxToolCalls < 0) {
            throw new IllegalArgumentException("maxToolCalls 不能小于 0");
        }
        if (maxDuration.isZero() || maxDuration.isNegative()) {
            throw new IllegalArgumentException("maxDuration 必须大于 0");
        }
    }
}
