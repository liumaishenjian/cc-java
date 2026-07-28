package io.github.liumaishenjian.ccjava.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * 限制单次 Run 可以消耗的模型回合、Tool Call、时间和模型重试次数。
 *
 * <p>模型回合上限至少为 1。Tool 上限可以为 0，用于显式禁止 Tool；
 * 同一模型回合的多个 Tool Call 作为原子批次预检，预算不足时整批不执行。
 * 模型重试次数表示首次调用之外允许的额外尝试，并且只适用于首个可见
 * 文本增量之前发生的可重试错误。</p>
 *
 * @param maxModelTurns   单次 Run 允许的最大逻辑模型回合数
 * @param maxToolCalls    单次 Run 允许的最大 Tool Call 数
 * @param maxRunDuration  单次 Run 允许的最大持续时间
 * @param maxModelRetries 每个逻辑模型回合允许的额外 Provider 尝试数
 * @since 0.1.0
 */
public record AgentLimits(
        int maxModelTurns,
        int maxToolCalls,
        Duration maxRunDuration,
        int maxModelRetries) {

    private static final Duration DEFAULT_RUN_DURATION = Duration.ofMinutes(5);
    private static final int DEFAULT_MODEL_RETRIES = 1;

    /** 保守的 S01 默认限制。 */
    public static final AgentLimits DEFAULT =
            new AgentLimits(16, 32, DEFAULT_RUN_DURATION, DEFAULT_MODEL_RETRIES);

    /**
     * 使用默认时间和模型重试预算创建兼容 S01 的限制。
     *
     * @param maxModelTurns 单次 Run 允许的最大逻辑模型回合数
     * @param maxToolCalls 单次 Run 允许的最大 Tool Call 数
     */
    public AgentLimits(int maxModelTurns, int maxToolCalls) {
        this(
                maxModelTurns,
                maxToolCalls,
                DEFAULT_RUN_DURATION,
                DEFAULT_MODEL_RETRIES);
    }

    /**
     * 校验预算边界后创建运行限制。
     *
     * @param maxModelTurns 单次 Run 允许的最大逻辑模型回合数
     * @param maxToolCalls 单次 Run 允许的最大 Tool Call 数
     * @param maxRunDuration 单次 Run 允许的最大持续时间
     * @param maxModelRetries 每个逻辑回合允许的额外 Provider 尝试数
     * @throws NullPointerException 最大持续时间为空时
     * @throws IllegalArgumentException 任一预算无效时
     */
    public AgentLimits {
        maxRunDuration = Objects.requireNonNull(
                maxRunDuration,
                "maxRunDuration 不能为空");
        if (maxModelTurns < 1) {
            throw new IllegalArgumentException("maxModelTurns 必须大于 0");
        }
        if (maxToolCalls < 0) {
            throw new IllegalArgumentException("maxToolCalls 不能小于 0");
        }
        if (maxRunDuration.isZero() || maxRunDuration.isNegative()) {
            throw new IllegalArgumentException("maxRunDuration 必须大于 0");
        }
        if (maxModelRetries < 0) {
            throw new IllegalArgumentException("maxModelRetries 不能小于 0");
        }
    }
}
