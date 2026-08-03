package io.github.liumaishenjian.ccjava.tools.local.workspace;

import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 把 WorkspaceGuard 的真实路径结论投影为 Permission Hard Denial 条件。
 *
 * <p>该预检只处理 {@code apply_patch/write_file} 的目标路径，避免 Symlink/Junction 逃逸
 * 进入可审批路径。返回允许后具体 Tool 在执行和提交前仍必须重新运行 WorkspaceGuard，
 * 因此本类型不替代 Adapter 的 TOCTOU 检查，也不是 OS Sandbox。</p>
 *
 * @since 0.5.0
 */
public final class WorkspaceWriteHardDenial implements Predicate<PermissionSelector> {

    private final WorkspaceGuard guard;

    /**
     * 创建 Workspace 写入预拒绝器。
     *
     * @param guard 与本地文件 Tool 共享的 WorkspaceGuard
     */
    public WorkspaceWriteHardDenial(WorkspaceGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard 不能为空");
    }

    /**
     * 判断写入 selector 是否命中不可审批的路径安全边界。
     *
     * @param selector 已由 Core 规范化的可信调用范围
     * @return 越界、敏感、链接逃逸或不可安全解释时为 {@code true}
     */
    @Override
    public boolean test(PermissionSelector selector) {
        Objects.requireNonNull(selector, "selector 不能为空");
        if (selector.toolWide()) {
            return true;
        }
        try {
            switch (selector.toolName()) {
                case "apply_patch" -> guard.requireRegularFile(selector.value());
                case "write_file" -> guard.requireNewFile(selector.value());
                default -> {
                    return false;
                }
            }
            return false;
        } catch (WorkspaceAccessException exception) {
            return switch (exception.error().code()) {
                case INVALID_PATH,
                        WORKSPACE_BOUNDARY_VIOLATION,
                        LINK_ESCAPE,
                        SENSITIVE_PATH -> true;
                case PATH_NOT_FOUND,
                        PATH_TYPE_MISMATCH,
                        FILE_CONFLICT -> false;
                default -> true;
            };
        }
    }
}
