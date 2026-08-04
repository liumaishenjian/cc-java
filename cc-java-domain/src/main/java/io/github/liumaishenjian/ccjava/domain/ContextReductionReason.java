package io.github.liumaishenjian.ccjava.domain;

/**
 * Context Reduction 的隐私安全原因码。
 *
 * <p>原因码只描述决策分类，不携带完整 Prompt、源码、Tool 输出或记忆正文。</p>
 *
 * @since 0.7.0
 */
public enum ContextReductionReason {

    /** 原始请求低于容量预算。 */
    WITHIN_CAPACITY,

    /** 已缩减单个高体积载荷。 */
    LARGE_PAYLOAD_REDUCED,

    /** 已清理旧 Tool Result 正文。 */
    OLD_TOOL_RESULT_CLEANED,

    /** 已组合应用多个确定性策略。 */
    MULTIPLE_REDUCTIONS_APPLIED,

    /** 完整但受保护的活动 Tool 批次禁止进入可缩减边界。 */
    ACTIVE_OR_PROTECTED_TOOL_BATCH,

    /** Canonical Transcript 的 Tool Call/Result 协议不完整或非法。 */
    INVALID_TOOL_PROTOCOL,

    /** 当前实现没有能够安全释放足够预算的候选。 */
    NO_SAFE_REDUCTION_AVAILABLE,

    /** 请求在候选提交前被取消。 */
    CANCELLED
}
