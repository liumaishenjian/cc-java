package io.github.liumaishenjian.ccjava.tools.local;

import io.github.liumaishenjian.ccjava.tools.local.git.GitReadClient;
import java.util.Objects;

/**
 * Session 打开时记录的非 Secret Workspace Git 摘要。
 *
 * @param repository 是否为 Git work tree
 * @param branch 当前分支或 {@code unknown}
 * @param staged staged 条目数
 * @param unstaged unstaged 条目数
 * @param untracked untracked 条目数
 * @since 0.3.0
 */
public record WorkspaceSnapshot(
        boolean repository,
        String branch,
        int staged,
        int unstaged,
        int untracked) {

    /** 校验安全摘要。 */
    public WorkspaceSnapshot {
        branch = Objects.requireNonNull(branch, "branch 不能为空");
        if (branch.isBlank() || staged < 0 || unstaged < 0 || untracked < 0) {
            throw new IllegalArgumentException("WorkspaceSnapshot 字段无效");
        }
    }

    /**
     * 非 Git Workspace 的稳定摘要。
     *
     * @return repository=false 且计数为零的摘要
     */
    public static WorkspaceSnapshot nonRepository() {
        return new WorkspaceSnapshot(false, "none", 0, 0, 0);
    }

    /**
     * 从固定 porcelain 读取构造摘要，不保留路径正文。
     *
     * @param git 固定 Workspace 的只读 Git Adapter
     * @return 非 Git 或读取失败时返回 non-repository 摘要
     */
    public static WorkspaceSnapshot capture(GitReadClient git) {
        try {
            String branch = "unknown";
            int staged = 0;
            int unstaged = 0;
            int untracked = 0;
            for (String line : git.status().stdout().lines().toList()) {
                if (line.startsWith("## ")) {
                    branch = line.substring(3).strip();
                } else if (line.length() >= 2) {
                    if (line.startsWith("??")) {
                        untracked++;
                    } else {
                        if (line.charAt(0) != ' ') {
                            staged++;
                        }
                        if (line.charAt(1) != ' ') {
                            unstaged++;
                        }
                    }
                }
            }
            return new WorkspaceSnapshot(true, branch, staged, unstaged, untracked);
        } catch (GitReadClient.GitReadException exception) {
            return nonRepository();
        }
    }
}
