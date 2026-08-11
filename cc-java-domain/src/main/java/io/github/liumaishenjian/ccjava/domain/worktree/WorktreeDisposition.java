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
    /** Worktree 已创建但当前没有 active owner。 */
    READY,
    /** 当前进程持有 active owner。 */
    IN_USE,
    /** 调用方明确选择保留目录与分支。 */
    KEPT,
    /** Worktree registration、目录与临时分支均已删除。 */
    REMOVED,
    /** Worktree registration/目录已删除，但分支因安全失败保留。 */
    REMOVED_BRANCH_PRESERVED,
    /** 无法证明安全清理，保留所有可恢复现场。 */
    FAILED_PRESERVED
}
