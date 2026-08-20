package io.github.liumaishenjian.ccjava.domain;

/**
 * 交互预算续租或终止的稳定原因。
 *
 * @since 0.15.0
 */
public enum BudgetGovernanceReason {
    /** 成功 Tool Result 证明 Run 仍有进展。 */ PROGRESS_EXTENDED,
    /** 达到调用方显式硬上限。 */ EXPLICIT_LIMIT,
    /** adaptive Run 没有可证明进展。 */ NO_PROGRESS,
    /** 达到 adaptive 的绝对安全 ceiling。 */ ABSOLUTE_LIMIT
}
