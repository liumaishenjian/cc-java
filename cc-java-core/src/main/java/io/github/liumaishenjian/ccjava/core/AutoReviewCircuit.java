package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.RunId;
import java.util.Objects;

/**
 * 由单个 Run 拥有的自动审查连续 non-allow 熔断器。
 *
 * <p>严格 DENY 与 PROVIDER/TIMEOUT/PARSE/INTERNAL failure 都累计；ALLOW_ONCE 清零；取消不计数。
 * 默认第三次连续 non-allow 令当前拒绝携带 after-current stop。全部操作与 close 在线性锁内完成。</p>
 * @since 0.15.0
 */
public final class AutoReviewCircuit implements AutoCloseable {
    /** 默认允许的连续 non-allow 次数。 */
    public static final int DEFAULT_MAX_FAILURES = 3;

    /** 进入一次 reviewer 调用前的原子检查结果。 */
    public enum AcquireStatus {
        /** 当前调用可以进入 reviewer。 */
        ACQUIRED,
        /** 连续 non-allow 已达到阈值。 */
        CIRCUIT_OPEN,
        /** 所属 Run 已关闭。 */
        RUN_CLOSED
    }
    private final RunId runId;
    private final int threshold;
    private int consecutiveNonAllows;
    private boolean closed;

    /**
     * 使用默认阈值创建 Run-owned circuit。
     *
     * @param runId 唯一拥有该状态的 Run
     */
    public AutoReviewCircuit(RunId runId) {
        this(runId, DEFAULT_MAX_FAILURES);
    }

    /**
     * 使用显式阈值创建 Run-owned circuit，主要供确定性测试使用。
     *
     * @param runId 唯一拥有该状态的 Run
     * @param threshold 连续 non-allow 的正数停止阈值
     */
    public AutoReviewCircuit(RunId runId, int threshold) {
        this.runId = Objects.requireNonNull(runId, "runId 不能为空");
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold 必须为正数");
        }
        this.threshold = threshold;
    }

    /**
     * 在调用 Gateway 前原子检查 Run 所有权、关闭状态与阈值。
     *
     * @param candidate 当前调用声明的 Run
     * @return 可以进入、熔断已开或 Run 已关闭
     */
    public synchronized AcquireStatus acquire(RunId candidate) {
        requireOwner(candidate);
        if (closed) return AcquireStatus.RUN_CLOSED;
        return consecutiveNonAllows >= threshold ? AcquireStatus.CIRCUIT_OPEN : AcquireStatus.ACQUIRED;
    }
    /**
     * 记录一次允许并清零连续 non-allow。
     *
     * @param candidate 当前调用声明的 Run
     */
    public synchronized void recordAllow(RunId candidate) {
        requireOpen(candidate);
        consecutiveNonAllows = 0;
    }

    /**
     * 记录一次拒绝或失败关闭。
     *
     * @param candidate 当前调用声明的 Run
     * @return 当前 non-allow 是否达到阈值；调用方须先完成当前拒绝
     */
    public synchronized boolean recordNonAllow(RunId candidate) {
        requireOpen(candidate);
        if (consecutiveNonAllows < threshold) {
            consecutiveNonAllows++;
        }
        return consecutiveNonAllows >= threshold;
    }

    /**
     * 查询当前连续 non-allow 数量。
     *
     * @return 当前连续 non-allow 数量
     */
    public synchronized int consecutiveFailures() {
        return consecutiveNonAllows;
    }

    /** 关闭 Run-owned 状态并清除计数，关闭后不得再记录 verdict。 */
    @Override
    public synchronized void close() {
        closed = true;
        consecutiveNonAllows = 0;
    }

    private void requireOpen(RunId candidate) {
        requireOwner(candidate);
        if (closed) {
            throw new IllegalStateException("Auto Review circuit 已关闭");
        }
    }

    private void requireOwner(RunId candidate) {
        Objects.requireNonNull(candidate, "candidateRunId 不能为空");
        if (!runId.equals(candidate)) {
            throw new IllegalArgumentException("Auto Review circuit 不能跨 Run 使用");
        }
    }
}
