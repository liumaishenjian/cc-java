package io.github.liumaishenjian.ccjava.cli.instructions;

import io.github.liumaishenjian.ccjava.tools.local.workspace.ValidatedWorkspacePath;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.util.Objects;

/**
 * 由 Workspace Adapter 证明存在、类型和 containment 的指令激活目标。
 *
 * <p>构造器不对外开放，调用方只能经 {@link #file(WorkspaceGuard, String)} 或
 * {@link #directory(WorkspaceGuard, String)} 取得实例，不能把模型或仓库文本直接伪装成
 * 已验证目标。该类型只保存 workspace-relative 协议标识，不公开真实路径。</p>
 *
 * @since 0.8.0
 */
public final class VerifiedInstructionTarget {
    private final String protocolPath;
    private final Kind kind;

    private VerifiedInstructionTarget(String protocolPath, Kind kind) {
        this.protocolPath = Objects.requireNonNull(protocolPath, "protocolPath 不能为空");
        this.kind = Objects.requireNonNull(kind, "kind 不能为空");
    }

    /**
     * 验证一个普通文件目标。
     *
     * @param guard 已固定的 Workspace 守卫
     * @param rawRelativePath 不可信的 workspace-relative 输入
     * @return 已验证文件目标
     * @throws WorkspaceAccessException 路径缺失、越界、敏感或非普通文件时
     */
    public static VerifiedInstructionTarget file(WorkspaceGuard guard, String rawRelativePath)
            throws WorkspaceAccessException {
        return from(Objects.requireNonNull(guard, "guard 不能为空").requireRegularFile(rawRelativePath), Kind.FILE);
    }

    /**
     * 验证一个目录目标。
     *
     * @param guard 已固定的 Workspace 守卫
     * @param rawRelativePath 不可信的 workspace-relative 输入
     * @return 已验证目录目标
     * @throws WorkspaceAccessException 路径缺失、越界、敏感或非目录时
     */
    public static VerifiedInstructionTarget directory(WorkspaceGuard guard, String rawRelativePath)
            throws WorkspaceAccessException {
        return from(Objects.requireNonNull(guard, "guard 不能为空").requireDirectory(rawRelativePath), Kind.DIRECTORY);
    }

    private static VerifiedInstructionTarget from(ValidatedWorkspacePath path, Kind kind) {
        return new VerifiedInstructionTarget(path.protocolPath(), kind);
    }

    /**
     * 返回已验证的 workspace-relative 协议路径。
     *
     * @return 不含真实路径的 `/` 分隔逻辑标识
     */
    public String protocolPath() {
        return protocolPath;
    }

    /**
     * 返回经过 Adapter 证明的目标类型。
     *
     * @return 文件或目录类型
     */
    public Kind kind() {
        return kind;
    }

    /** 已验证目标的受限类型。 */
    public enum Kind {
        /** 普通文件，规划从其父目录开始。 */
        FILE,
        /** Workspace 内目录，规划从自身开始。 */
        DIRECTORY
    }
}
