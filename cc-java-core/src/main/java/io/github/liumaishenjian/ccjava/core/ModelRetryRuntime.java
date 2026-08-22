package io.github.liumaishenjian.ccjava.core;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 为模型重试提供可替换的随机数与可取消等待边界。
 *
 * <p>生产实现只使用本机单调等待；测试可以注入确定性 random/sleeper，避免依赖真实墙钟。
 * Run deadline 的权威剩余预算仍来自 {@link CancellationToken#remainingTime()}，本类型不创建
 * 第二套 deadline。</p>
 *
 * @since 0.1.0
 */
public interface ModelRetryRuntime {

    /**
     * 返回用于 jitter 的 {@code [0, 1)} 值。
     *
     * @return 有界随机值
     */
    double nextRandom();

    /**
     * 等待指定时长，并在取消时立即结束。
     *
     * @param delay 非负等待时长
     * @param cancellation 当前 Run 取消令牌
     * @throws ModelGatewayException 等待被取消或线程中断时
     */
    void await(Duration delay, CancellationToken cancellation) throws ModelGatewayException;

    /**
     * 返回生产用共享实现。
     *
     * @return 使用 ThreadLocalRandom 与可取消 latch 的实现
     */
    static ModelRetryRuntime system() {
        return SystemRuntime.INSTANCE;
    }

    /** 生产共享实现。 */
    enum SystemRuntime implements ModelRetryRuntime {
        /** 单例。 */
        INSTANCE;

        @Override
        public double nextRandom() {
            return ThreadLocalRandom.current().nextDouble();
        }

        @Override
        public void await(Duration delay, CancellationToken cancellation) throws ModelGatewayException {
            Objects.requireNonNull(delay, "delay 不能为空");
            Objects.requireNonNull(cancellation, "cancellation 不能为空");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay 不能为负数");
            }
            if (delay.isZero()) {
                if (cancellation.isCancellationRequested()) {
                    throw cancelled(null);
                }
                return;
            }
            CountDownLatch cancelled = new CountDownLatch(1);
            try (CancellationToken.Registration ignored = cancellation.onCancellation(cancelled::countDown)) {
                try {
                    boolean signalled = cancelled.await(delay.toNanos(), TimeUnit.NANOSECONDS);
                    if (signalled || cancellation.isCancellationRequested()) {
                        throw cancelled(null);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw cancelled(interrupted);
                }
            }
        }

        private static ModelGatewayException cancelled(Throwable cause) {
            return new ModelGatewayException(
                    ModelGatewayException.FailureKind.CANCELLED,
                    "Model retry wait cancelled",
                    cause);
        }
    }
}
