package io.github.liumaishenjian.ccjava.domain.subagent;

/**
 * 隐私安全的子任务失败分类，不携带异常或 Provider 原文。
 *
 * @since 0.12.0
 */
public enum ChildTaskFailureCode {
    /** 任务未失败。 */
    NONE,
    /** Definition 缺失、冲突或企图放宽。 */
    DEFINITION_REJECTED,
    /** 父预算无法原子预留。 */
    BUDGET_REJECTED,
    /** 公平有界队列已满。 */
    QUEUE_FULL,
    /** 委托深度超过共享 ceiling。 */
    DEPTH_EXCEEDED,
    /** 受信 start Hook 阻断任务。 */
    START_HOOK_BLOCKED,
    /** 子 Runtime 以非取消错误结束。 */
    RUNTIME_FAILED,
    /** Durable task journal 无法提交。 */
    JOURNAL_FAILED,
    /** 父级或显式请求取消。 */
    CANCELLED,
    /** 子任务 deadline 到期。 */
    TIMEOUT,
    /** 中断来源无法安全分类。 */
    INTERRUPTED_UNKNOWN
}
