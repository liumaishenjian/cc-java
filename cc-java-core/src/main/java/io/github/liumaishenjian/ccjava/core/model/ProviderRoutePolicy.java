package io.github.liumaishenjian.ccjava.core.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Provider 路由的 attempt、成本、重试等待与每次请求时间预算配置。
 *
 * <p>长期存活的 Router 复用本配置；相对 {@code maxDuration} 会在每次 {@code complete}
 * 开始时换算为新的共享 deadline。显式绝对 deadline 构造器只用于调用方确实需要固定期限或
 * 使用固定 {@link Clock} 的确定性测试。当前 Gateway 无法在返回前报告 token/cost，因此成本仅是
 * 每次 attempt 的保守单位；Provider typed Retry-After 会被截断到 {@code maxRetryDelay}，并与取消、
 * 共享 deadline 和 attempt/cost budget 共同收敛。</p>
 *
 * @since 0.1.0
 */
public final class ProviderRoutePolicy {
    private final int maxAttempts;
    private final Duration maxDuration;
    private final Instant fixedDeadline;
    private final Duration maxRetryDelay;
    private final long maxCostUnits;
    private final long attemptCostUnits;
    private final Clock clock;

    /**
     * 创建每次请求重新起算的相对时间预算。
     *
     * @param maxAttempts 单次请求允许的最大 Provider attempt 数
     * @param maxDuration 单次请求从入口起算的最大总时长
     * @param maxRetryDelay 单次 Retry-After 允许等待的上限
     * @param maxCostUnits 单次请求成本预算；负一表示不限制
     * @param attemptCostUnits 每次 attempt 消耗的保守成本单位
     * @param clock 解析共享 deadline 的时钟
     */
    public ProviderRoutePolicy(
            int maxAttempts,
            Duration maxDuration,
            Duration maxRetryDelay,
            long maxCostUnits,
            long attemptCostUnits,
            Clock clock) {
        this(maxAttempts, Objects.requireNonNull(maxDuration, "maxDuration 不能为空"), null,
                maxRetryDelay, maxCostUnits, attemptCostUnits, clock);
        if (maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("maxDuration 非法");
        }
    }

    /**
     * 创建显式固定绝对期限预算。
     *
     * <p>该期限不会在后续 {@code complete} 调用时刷新；适用于外层 Run 已经持有绝对
     * deadline 的场景和固定时钟测试。</p>
     *
     * @param maxAttempts 单次请求允许的最大 Provider attempt 数
     * @param deadline 外层 Run 已持有的绝对期限
     * @param maxRetryDelay 单次 Retry-After 允许等待的上限
     * @param maxCostUnits 单次请求成本预算；负一表示不限制
     * @param attemptCostUnits 每次 attempt 消耗的保守成本单位
     * @param clock 检查绝对期限与等待预算的时钟
     */
    public ProviderRoutePolicy(
            int maxAttempts,
            Instant deadline,
            Duration maxRetryDelay,
            long maxCostUnits,
            long attemptCostUnits,
            Clock clock) {
        this(maxAttempts, null, Objects.requireNonNull(deadline, "deadline 不能为空"),
                maxRetryDelay, maxCostUnits, attemptCostUnits, clock);
    }

    private ProviderRoutePolicy(
            int maxAttempts,
            Duration maxDuration,
            Instant fixedDeadline,
            Duration maxRetryDelay,
            long maxCostUnits,
            long attemptCostUnits,
            Clock clock) {
        if (maxAttempts < 1 || maxAttempts > 32) {
            throw new IllegalArgumentException("maxAttempts 非法");
        }
        this.maxAttempts = maxAttempts;
        this.maxDuration = maxDuration;
        this.fixedDeadline = fixedDeadline;
        this.maxRetryDelay = Objects.requireNonNull(maxRetryDelay, "maxRetryDelay 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        if (maxRetryDelay.isNegative() || attemptCostUnits < 0 || maxCostUnits < -1) {
            throw new IllegalArgumentException("route budget 非法");
        }
        this.maxCostUnits = maxCostUnits;
        this.attemptCostUnits = attemptCostUnits;
    }

    /**
     * 创建无成本限制、每次请求重新获得五分钟期限的共享预算。
     *
     * @return 默认 attempt、deadline 与重试等待策略
     */
    public static ProviderRoutePolicy defaults() {
        return new ProviderRoutePolicy(
                4, Duration.ofMinutes(5), Duration.ofSeconds(30), -1, 0, Clock.systemUTC());
    }

    /** 返回配置值。
     *
     * @return 单次请求最大 attempt 数 */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** 返回配置值。
     *
     * @return 单次重试等待上限 */
    public Duration maxRetryDelay() {
        return maxRetryDelay;
    }

    /** 返回配置值。
     *
     * @return 请求成本上限；负一表示不限制 */
    public long maxCostUnits() {
        return maxCostUnits;
    }

    /** 返回配置值。
     *
     * @return 每次 attempt 消耗的保守成本单位 */
    public long attemptCostUnits() {
        return attemptCostUnits;
    }

    /** 返回配置值。
     *
     * @return deadline 与等待预算使用的时钟 */
    public Clock clock() {
        return clock;
    }

    /**
     * 在一次 {@code complete} 的入口解析该次调用共享的绝对期限。
     *
     * @return 固定期限或从当前时钟加相对预算得到的期限
     */
    public Instant deadlineForRequest() {
        return fixedDeadline != null ? fixedDeadline : clock.instant().plus(maxDuration);
    }
}
