package io.github.liumaishenjian.ccjava.domain;

/**
 * 一次 Projection Reduction 的结构化终态。
 *
 * @since 0.7.0
 */
public enum ContextReductionStatus {

    /** 原始 Projection 已满足预算，未应用策略。 */
    UNCHANGED,

    /** 已安全应用一个或多个策略并满足预算。 */
    REDUCED,

    /** 当前可用策略无法在不破坏协议或活动边界的前提下满足预算。 */
    CONTEXT_LIMIT_REACHED,

    /** Reduction 在提交候选前被取消。 */
    CANCELLED
}
