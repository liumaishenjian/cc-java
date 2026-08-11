package io.github.liumaishenjian.ccjava.domain.worktree;

/**
 * Git Worktree lease 的不透明身份。
 *
 * @param value 以 wt- 开头的有界 ASCII 标识
 * @since 0.12.0
 */
public record WorktreeLeaseId(String value) {
    /** 校验不透明 lease ID 使用固定前缀与有界 ASCII。 */
    public WorktreeLeaseId {
        if (value == null || !value.matches("wt-[a-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("lease ID 无效");
        }
    }
}
