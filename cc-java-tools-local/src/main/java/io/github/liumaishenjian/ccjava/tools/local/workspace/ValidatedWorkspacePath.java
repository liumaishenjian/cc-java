package io.github.liumaishenjian.ccjava.tools.local.workspace;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 已通过逻辑路径、真实路径和敏感策略校验的 Workspace 内路径。
 *
 * <p>该值仅在 tools-local 内流转，Domain/Core 不接触文件系统类型。{@code protocolPath}
 * 始终使用 `/`，用于安全结果展示；{@code realPath} 只能交给本地 Adapter，不得写入模型错误
 * 或普通遥测。</p>
 *
 * @param realPath 已解析且位于真实 Workspace 内的目标
 * @param protocolPath 相对 Workspace 的稳定协议路径
 * @since 0.3.0
 */
public record ValidatedWorkspacePath(Path realPath, String protocolPath) {

    /** 校验路径值对象。 */
    public ValidatedWorkspacePath {
        realPath = Objects.requireNonNull(realPath, "realPath 不能为空");
        protocolPath = Objects.requireNonNull(protocolPath, "protocolPath 不能为空");
        if (protocolPath.isBlank()) {
            throw new IllegalArgumentException("protocolPath 不能为空白");
        }
    }
}
