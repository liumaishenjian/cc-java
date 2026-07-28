package io.github.liumaishenjian.ccjava.core;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime 传给单次 Provider 尝试的流观察、取消和截止时间上下文。
 *
 * <p>该类型只包含项目自有端口和 JDK 时间类型。Spring AI、Reactor 或具体
 * Provider 的订阅对象必须留在 Adapter 内部。相同逻辑 Model Turn 的重试会
 * 复用请求和截止时间，但递增 {@code attemptNumber}。</p>
 *
 * @param observer          文本增量观察者
 * @param cancellationToken 当前 Run 的取消信号
 * @param deadline          当前 Run 的绝对截止时间；兼容旧调用时可以为空
 * @param attemptNumber     当前逻辑回合内从 1 开始的 Provider 尝试序号
 * @since 0.1.0
 */
public record ModelCallContext(
        ModelTurnObserver observer,
        CancellationToken cancellationToken,
        Optional<Instant> deadline,
        int attemptNumber) {

    /**
     * 校验模型调用上下文。
     *
     * @param observer 文本增量观察者
     * @param cancellationToken 当前 Run 的取消信号
     * @param deadline 当前 Run 的绝对截止时间
     * @param attemptNumber 从 1 开始的 Provider 尝试序号
     * @throws NullPointerException 任一引用或 Optional 容器为空时
     * @throws IllegalArgumentException 尝试序号小于 1 时
     */
    public ModelCallContext {
        observer = Objects.requireNonNull(observer, "observer 不能为空");
        cancellationToken = Objects.requireNonNull(
                cancellationToken,
                "cancellationToken 不能为空");
        deadline = Objects.requireNonNull(deadline, "deadline 不能为空");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber 必须从 1 开始");
        }
    }

    /**
     * 创建没有流输出、取消或截止时间的兼容调用上下文。
     *
     * @return 无边界上下文
     */
    public static ModelCallContext unbounded() {
        return new ModelCallContext(
                ModelTurnObserver.noop(),
                CancellationToken.none(),
                Optional.empty(),
                1);
    }

    /**
     * 使用调用方提供的 Clock 判断截止时间是否已经到达。
     *
     * @param clock 确定性时间来源
     * @return 当前时间等于或晚于截止时间时为 {@code true}
     */
    public boolean deadlineReached(Clock clock) {
        Objects.requireNonNull(clock, "clock 不能为空");
        return deadline
                .map(value -> !clock.instant().isBefore(value))
                .orElse(false);
    }
}
