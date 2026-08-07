package io.github.liumaishenjian.ccjava.tools.local;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.tools.local.git.GitReadClient;
import io.github.liumaishenjian.ccjava.tools.local.tool.GitDiffTool;
import io.github.liumaishenjian.ccjava.tools.local.tool.GitStatusTool;
import io.github.liumaishenjian.ccjava.tools.local.tool.ListFilesTool;
import io.github.liumaishenjian.ccjava.tools.local.tool.ReadFileTool;
import io.github.liumaishenjian.ccjava.tools.local.tool.SearchTextTool;
import io.github.liumaishenjian.ccjava.tools.local.text.WorkspaceReadRegistry;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 为一个真实 Workspace 创建稳定顺序的 S03 本地只读工具集合。
 *
 * <p>工厂共享一个 {@link WorkspaceGuard}，但不创建 Registry、Pipeline 或 Permission；这些
 * 控制仍由 CLI Composition Root 统一装配。工具按 {@code list_files}、
 * {@code search_text}、{@code read_file}、{@code git_status}、{@code git_diff} 的协议顺序
 * 注册，使模型 Schema 和测试保持确定性。</p>
 *
 * @since 0.3.0
 */
public final class LocalReadTools {

    private LocalReadTools() {
    }

    /**
     * 创建绑定 Workspace 的只读 Tool。
     *
     * @param workspace 已由 CLI 解析的 Workspace
     * @return 稳定且不可变的 Tool 列表
     * @throws IOException Workspace 无法解析为真实目录时
     */
    public static List<AgentTool> create(Path workspace) throws IOException {
        WorkspaceGuard guard = new WorkspaceGuard(
                Objects.requireNonNull(workspace, "workspace 不能为空"));
        return create(guard);
    }

    /**
     * 使用 Composition Root 已固定的 Guard 创建只读 Tool。
     *
     * @param guard 与写工具、指令和 Git 快照共享的安全边界
     * @return 稳定且不可变的只读 Tool 列表
     */
    public static List<AgentTool> create(WorkspaceGuard guard) {
        return create(guard, new WorkspaceReadRegistry());
    }

    /**
     * 创建与写工具共享 Read 证据登记表的只读 Tool。
     *
     * <p>共享登记表通过组合注入，使 {@code read_file} 记录的行范围证据成为
     * {@code apply_patch} 的写入前置条件，而两个 Tool 之间不产生直接依赖。</p>
     *
     * @param guard 与写工具、指令和 Git 快照共享的安全边界
     * @param readRegistry 有界 Read 证据登记表
     * @return 稳定且不可变的只读 Tool 列表
     */
    public static List<AgentTool> create(
            WorkspaceGuard guard,
            WorkspaceReadRegistry readRegistry) {
        Objects.requireNonNull(guard, "guard 不能为空");
        Objects.requireNonNull(readRegistry, "readRegistry 不能为空");
        GitReadClient git = new GitReadClient(guard.workspace());
        return List.of(
                new ListFilesTool(guard),
                new SearchTextTool(guard),
                new ReadFileTool(guard, readRegistry),
                new GitStatusTool(guard, git),
                new GitDiffTool(guard, git));
    }
}
