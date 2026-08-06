package io.github.liumaishenjian.ccjava.domain.command;

/**
 * 不携带外部文本的命令终态代码。
 *
 * @since 0.8.0
 */
public enum SessionCommandResultCode {
    /** 命令已按契约完成。 */
    OK,
    /** 命令要求 idle Runtime，但当前存在活动 Run。 */
    ACTIVE_RUN,
    /** 输入未满足 Domain 契约。 */
    INVALID_ARGUMENT,
    /** 所需的已发布只读视图尚不存在。 */
    UNAVAILABLE,
    /** 当前不存在安全可调用的实现。 */
    NOT_AVAILABLE,
    /** 命令被保留给尚未接入的 Application/Surface 能力。 */
    DEFERRED,
    /** 命令在执行前被取消。 */
    CANCELLED,
    /** 内部故障被收敛后的安全代码。 */
    INTERNAL_FAILURE,
    /** 当前 dispatcher 的有界 request budget 已耗尽。 */
    REQUEST_BUDGET_EXHAUSTED
}
