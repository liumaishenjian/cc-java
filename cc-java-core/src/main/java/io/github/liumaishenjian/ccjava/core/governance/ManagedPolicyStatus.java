package io.github.liumaishenjian.ccjava.core.governance;

/**
 * Managed Policy 经过可信来源选择后的状态。
 *
 * @since 0.1.0
 */
public enum ManagedPolicyStatus {
    /** 使用已验证的当前策略。 */
    CURRENT,
    /** 当前策略不可用，使用已验证的 last-known-good。 */
    LKG,
    /** 固定机器来源未声明策略。 */
    ABSENT,
    /** 非安全相关的无效声明被忽略。 */
    INVALID_IGNORED,
    /** 安全相关声明无可信 current 或 LKG，必须失败关闭。 */
    FAIL_CLOSED
}
