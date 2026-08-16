package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ApprovalReviewResult;
import java.util.Objects;
import java.util.Optional;

/**
 * 自动审查返回给 Pipeline 的封闭决定。
 *
 * <p>决定同时表达当前调用的结果与“当前拒绝完成后停止 Run”的信号。达到阈值的第三次
 * non-allow 仍保留本次 DENY/FAILED_CLOSED 语义，并设置 {@code stopAfterCurrentDeny}；
 * 后续调用不得再进入 Gateway。取消不构造该决定，而以 {@link java.util.concurrent.CancellationException}
 * 交还 AgentRuntime 的既有取消路径。</p>
 *
 * @param status 当前调用的收敛状态
 * @param failure Gateway 失败分类，仅 FAILED_CLOSED 时存在
 * @param stopAfterCurrentDeny 当前拒绝结果持久化并配对后是否停止 Run
 * @since 0.15.0
 */
public record AutoReviewDecision(Status status, Optional<ApprovalReviewResult.FailureKind> failure,
        boolean stopAfterCurrentDeny) {
    /** Pipeline 可消费的封闭收敛状态。 */
    public enum Status {
        /** 只允许当前调用。 */
        ALLOW_ONCE,
        /** reviewer 明确拒绝当前调用。 */
        DENY,
        /** reviewer 失败并拒绝当前调用。 */
        FAILED_CLOSED,
        /** 连续 non-allow 已达到阈值。 */
        CIRCUIT_OPEN,
        /** 当前 Run scope 已关闭。 */
        RUN_CLOSED,
        /** 输入并非 Hook 后最终 ASK。 */
        NOT_FINAL_ASK
    }

    /** 校验失败分类和 batch stop 信号只出现在允许的状态组合中。 */
    public AutoReviewDecision {
        status = Objects.requireNonNull(status, "status 不能为空");
        failure = Objects.requireNonNull(failure, "failure 不能为空");
        if ((status == Status.FAILED_CLOSED) != failure.isPresent()) {
            throw new IllegalArgumentException("只有 FAILED_CLOSED 必须携带 failure");
        }
        if (stopAfterCurrentDeny && status != Status.DENY && status != Status.FAILED_CLOSED) {
            throw new IllegalArgumentException("stop signal 必须伴随当前 deny/failure");
        }
    }

    /**
     * 创建只允许当前调用的决定。
     *
     * @return 只允许当前调用且不停止 Run 的决定
     */
    public static AutoReviewDecision allowOnce() {
        return new AutoReviewDecision(Status.ALLOW_ONCE, Optional.empty(), false);
    }

    /**
     * 创建 reviewer 明确拒绝。
     *
     * @param stop 当前结果配对后是否停止 Run
     * @return 严格拒绝决定
     */
    public static AutoReviewDecision deny(boolean stop) {
        return new AutoReviewDecision(Status.DENY, Optional.empty(), stop);
    }

    /**
     * 创建表示输入不是最终 ASK 的内部决定。
     *
     * @return 表示输入不是最终 ASK 的内部决定
     */
    public static AutoReviewDecision notFinalAsk() {
        return new AutoReviewDecision(Status.NOT_FINAL_ASK, Optional.empty(), false);
    }

    /**
     * 创建 reviewer 失败关闭决定。
     *
     * @param kind 固定失败分类
     * @param stop 当前结果配对后是否停止 Run
     * @return 失败关闭决定
     */
    public static AutoReviewDecision failed(ApprovalReviewResult.FailureKind kind, boolean stop) {
        return new AutoReviewDecision(Status.FAILED_CLOSED, Optional.of(kind), stop);
    }

    /**
     * 创建不再进入 Gateway 的停止决定。
     *
     * @param status 只能是 CIRCUIT_OPEN 或 RUN_CLOSED
     * @return 不包含当前 reviewer verdict 的停止决定
     */
    public static AutoReviewDecision stopped(Status status) {
        if (status != Status.CIRCUIT_OPEN && status != Status.RUN_CLOSED) {
            throw new IllegalArgumentException("typed stop 状态无效");
        }
        return new AutoReviewDecision(status, Optional.empty(), false);
    }
}
