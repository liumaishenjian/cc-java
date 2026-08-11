package io.github.liumaishenjian.ccjava.tools.local.worktree;

import io.github.liumaishenjian.ccjava.domain.worktree.WorktreeLease;
import java.nio.file.Path;

/**
 * 固定 argv Git Worktree 生命周期 Adapter 契约。
 *
 * <p>实现不得自动 commit/merge/push；无法证明 clean/identity/owner 时必须 preserve。</p>
 * @since 0.12.0
 */
public interface WorktreeManager {
    /**
     * 从精确 base commit 创建项目私有 Worktree。
     *
     * @param slug 安全且唯一的目录/分支短名
     * @param baseCommit 已验证的完整 Git commit identity
     * @return 新建且尚未进入的 lease
     */
    WorktreeLease create(String slug, String baseCommit);

    /**
     * 取得 lease 的 active owner 并返回固定工作目录。
     *
     * @param lease 要进入的 Worktree lease
     * @return 已重新验证身份的 Worktree 路径
     */
    Path enter(WorktreeLease lease);

    /**
     * 释放当前进程对 lease 的 active owner，不改变保留/删除选择。
     *
     * @param lease 要释放 active owner 的 Worktree lease
     */
    void leave(WorktreeLease lease);

    /**
     * 保留 Worktree 与分支，供后续人工处理。
     *
     * @param lease 要保留的 Worktree lease
     * @return 反映最终保留状态的 lease
     */
    WorktreeLease keep(WorktreeLease lease);

    /**
     * 仅在身份、clean 状态和提交边界均可证明时删除 Worktree。
     *
     * @param lease 要安全删除的 Worktree lease
     * @return 删除成功或失败后保留状态的 lease
     */
    WorktreeLease removeClean(WorktreeLease lease);
}
