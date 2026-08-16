package io.github.liumaishenjian.ccjava.domain;

/**
 * 面向产品 Surface 的三项 Permission 选择。
 *
 * <p>选择只映射到既有 {@link PermissionMode} 与独立 {@link ApprovalReviewer}，不会创建规则、
 * Session Grant 或绕过 Permission Pipeline。历史 {@link PermissionMode#ACCEPT_EDITS} 仅保留兼容，
 * 不属于本选择集合。</p>
 *
 * @since 0.15.0
 */
public enum PermissionSelection {

    /** 安全规划：只读模式，ASK 仍由用户 Surface 处理。 */
    PLAN(PermissionMode.PLAN, ApprovalReviewer.USER),

    /** 普通询问：默认模式下由用户处理最终 ASK。 */
    ASK(PermissionMode.DEFAULT, ApprovalReviewer.USER),

    /** 自动审查：默认模式下仅把最终 ASK 交给受控 reviewer。 */
    AUTO(PermissionMode.DEFAULT, ApprovalReviewer.AUTO_REVIEW);

    private final PermissionMode mode;
    private final ApprovalReviewer reviewer;

    PermissionSelection(PermissionMode mode, ApprovalReviewer reviewer) {
        this.mode = mode;
        this.reviewer = reviewer;
    }

    /**
     * 返回确定性 Permission Mode。
     *
     * @return 与本选择绑定的模式
     */
    public PermissionMode mode() {
        return mode;
    }

    /**
     * 返回最终 ASK 的审查主体。
     *
     * @return 与本选择绑定的 reviewer
     */
    public ApprovalReviewer reviewer() {
        return reviewer;
    }
}
