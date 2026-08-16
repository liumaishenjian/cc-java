package io.github.liumaishenjian.ccjava.domain;

/**
 * 收敛最终 {@link PermissionDecision#ASK} 的审查主体。
 *
 * <p>Reviewer 与 {@link PermissionMode} 正交：前者只决定 ASK 交给用户还是受控自动审查，
 * 后者仍决定规则、Effect 与安全模式默认值。Reviewer 不能覆盖 Hard Denial、显式 Deny、
 * PLAN 限制或 Tool Adapter 安全检查。</p>
 *
 * @since 0.15.0
 */
public enum ApprovalReviewer {

    /** 由现有交互或非交互 Approval Surface 收敛。 */
    USER,

    /** 由有界、失败关闭的自动审查端口收敛，允许时仅限当前调用。 */
    AUTO_REVIEW
}
