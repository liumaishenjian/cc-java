package io.github.liumaishenjian.ccjava.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime 持有的线程安全取消源。
 *
 * <p>调用 {@link #cancel()} 只改变取消状态并通知已注册 Adapter；它不决定
 * Agent Run 的最终状态，终态仍由 Runtime 状态机产生。</p>
 *
 * @since 0.1.0
 */
public final class CancellationSource {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();
    private final long deadlineNanos;
    private final CancellationToken token = new SourceToken();

    /**
     * 创建尚未取消且没有 deadline 的取消源。
     */
    public CancellationSource() {
        deadlineNanos = Long.MAX_VALUE;
    }

    /**
     * 创建绑定单调时钟 deadline 的取消源。
     *
     * <p>该 deadline 只负责向下游暴露不断缩短的剩余预算；实际到期触发仍由
     * {@code AgentRuntime} 的 deadline 任务调用 {@link #cancel()}，从而保留 timeout 与用户取消的
     * 首胜终态语义。</p>
     *
     * @param maxDuration 从创建时刻开始计算的正墙钟预算
     */
    public CancellationSource(Duration maxDuration) {
        Objects.requireNonNull(maxDuration, "maxDuration 不能为空");
        if (maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("maxDuration 必须大于 0");
        }
        long now = System.nanoTime();
        long nanos;
        try {
            nanos = maxDuration.toNanos();
        } catch (ArithmeticException overflow) {
            nanos = Long.MAX_VALUE;
        }
        deadlineNanos = nanos == Long.MAX_VALUE || Long.MAX_VALUE - now < nanos
                ? Long.MAX_VALUE
                : now + nanos;
    }

    /**
     * 返回只读取消 Token。
     *
     * @return 可传给 Adapter 的 Token
     */
    public CancellationToken token() {
        return token;
    }

    /**
     * 首次调用时通知全部回调；后续调用无效果。
     *
     * @return 本次调用是否首次触发取消
     */
    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (RuntimeException ignored) {
                // 取消已经线性化；单个 Adapter 清理失败不能阻止其余订阅者收到通知。
            }
        }
        callbacks.clear();
        return true;
    }

    private final class SourceToken implements CancellationToken {
        @Override
        public boolean isCancellationRequested() {
            return cancelled.get();
        }

        @Override
        public Registration onCancellation(Runnable action) {
            Objects.requireNonNull(action, "action 不能为空");
            if (cancelled.get()) {
                runCancellationAction(action);
                return () -> {
                };
            }
            callbacks.add(action);
            if (cancelled.get() && callbacks.remove(action)) {
                runCancellationAction(action);
            }
            return () -> callbacks.remove(action);
        }

        private void runCancellationAction(Runnable action) {
            try {
                action.run();
            } catch (RuntimeException ignored) {
                // 注册竞争发生在已取消状态时也保持取消 API 不向 Runtime 泄漏 Adapter 清理异常。
            }
        }

        @Override
        public Optional<Duration> remainingTime() {
            if (deadlineNanos == Long.MAX_VALUE) {
                return Optional.empty();
            }
            long remaining = deadlineNanos - System.nanoTime();
            return Optional.of(remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining));
        }
    }
}
