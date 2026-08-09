package io.github.liumaishenjian.ccjava.domain.worktree;

/**
 * Worktree 保守生命周期终态。
 *
 * <p>{@link #REMOVED_BRANCH_PRESERVED} 明确表示目录/registration 已删除但临时分支仍保留，
 * 避免把部分清理失败误报为“工作目录已保留”。</p>
 *
 * @since 0.12.0
 */
public enum WorktreeDisposition {
    READY,
    IN_USE,
    KEPT,
    REMOVED,
    REMOVED_BRANCH_PRESERVED,
    FAILED_PRESERVED
}
