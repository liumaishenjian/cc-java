package io.github.liumaishenjian.ccjava.domain.worktree;

import java.util.Objects;

/**
 * 不含本机绝对路径的 Worktree 规范租约。
 * @param id 不透明 lease
 * @param repositoryIdentity canonical repository digest
 * @param baseCommit 明确基线 commit
 * @param branch 宿主生成临时分支
 * @param opaqueRoot 可展示的项目内相对身份
 * @param disposition 当前 disposition
 * @since 0.12.0
 */
public record WorktreeLease(WorktreeLeaseId id, String repositoryIdentity, String baseCommit,
        String branch, String opaqueRoot, WorktreeDisposition disposition) {
    /** 校验 lease、仓库、commit、分支、相对 root 与终态均为规范值。 */
    public WorktreeLease {
        Objects.requireNonNull(id); Objects.requireNonNull(disposition);
        if (repositoryIdentity == null || !repositoryIdentity.matches("[0-9a-f]{64}")
                || baseCommit == null || !baseCommit.matches("[0-9a-f]{40,64}")
                || branch == null || !branch.matches("cc-java/s12/[a-z0-9-]{1,64}")
                || opaqueRoot == null || !opaqueRoot.matches("worktrees/[a-z0-9-]{1,64}")) throw new IllegalArgumentException("worktree lease 无效");
    }
}
