package io.github.liumaishenjian.ccjava.core;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 定义单个模型回合的有界重试次数和退避时间。
 *
 * <p>列表第一个元素表示第一次失败后的等待，元素数量必须等于
 * {@code maxAttempts - 1}。零等待只用于确定性测试；生产默认使用短退避，
 * 总墙钟上限仍由 Agent Run Deadline 控制。</p>
 *
 * @param maxAttempts 包含首次请求在内的最大尝试次数
 * @param delays 每次重试前的等待时间
 * @since 0.1.0
 */
public record ModelRetryPolicy(
        int maxAttempts,
        List<Duration> delays) {

    /** S02 默认：首次请求加两次短退避重试。 */
    public static final ModelRetryPolicy S02_DEFAULT = new ModelRetryPolicy(
            3,
            List.of(Duration.ofMillis(100), Duration.ofMillis(250)));

    /**
     * 校验策略边界。
     */
    public ModelRetryPolicy {
        delays = List.copyOf(Objects.requireNonNull(delays, "delays 不能为空"));
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须大于 0");
        }
        if (delays.size() != maxAttempts - 1) {
            throw new IllegalArgumentException("delays 数量必须等于 maxAttempts - 1");
        }
        if (delays.stream().anyMatch(delay ->
                delay == null || delay.isNegative())) {
            throw new IllegalArgumentException("重试等待不能为 null 或负数");
        }
    }

    /**
     * 返回指定失败次数后的等待。
     *
     * @param failedAttempt 从 1 开始的失败尝试序号
     * @return 下一次请求前的等待
     */
    public Duration delayAfter(int failedAttempt) {
        if (failedAttempt < 1 || failedAttempt >= maxAttempts) {
            throw new IllegalArgumentException("failedAttempt 不存在对应重试");
        }
        return delays.get(failedAttempt - 1);
    }
}
