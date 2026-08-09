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
    WorktreeLease create(String slug, String baseCommit);
    Path enter(WorktreeLease lease);
    /** 释放当前进程对 lease 的 active owner，不改变保留/删除选择。 */
    void leave(WorktreeLease lease);
    WorktreeLease keep(WorktreeLease lease);
    WorktreeLease removeClean(WorktreeLease lease);
}
