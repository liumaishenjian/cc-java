package io.github.liumaishenjian.ccjava.domain.worktree;

/** Git Worktree lease 的不透明身份。 @param value 有界 ASCII 标识 @since 0.12.0 */
public record WorktreeLeaseId(String value) {
    public WorktreeLeaseId { if (value == null || !value.matches("wt-[a-z0-9-]{1,64}")) throw new IllegalArgumentException("lease ID 无效"); }
}
