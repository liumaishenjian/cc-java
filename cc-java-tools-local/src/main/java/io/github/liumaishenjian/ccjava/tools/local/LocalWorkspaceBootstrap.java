package io.github.liumaishenjian.ccjava.tools.local;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.tools.local.git.GitReadClient;
import io.github.liumaishenjian.ccjava.tools.local.tool.ApplyPatchTool;
import io.github.liumaishenjian.ccjava.tools.local.tool.WriteFileTool;
import io.github.liumaishenjian.ccjava.tools.local.tool.RunCommandTool;
import io.github.liumaishenjian.ccjava.tools.local.command.LocalCommandExecutor;
import io.github.liumaishenjian.ccjava.tools.local.text.WorkspaceReadRegistry;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一次性组装 S03-S04 WorkspaceGuard、本地 Tool 和安全 Git 摘要。
 *
 * <p>Composition Root 使用该不可变结果，避免为 Tool 与 Snapshot 分别解析 Workspace。
 * Instructions 必须由 S08 的短生命周期投影单独加载，Bootstrap 不创建 Registry 或 Runtime，
 * 也不驱动 Agent Loop。</p>
 *
 * @param tools 按稳定协议顺序排列的五个只读、两个写入和一个命令 Tool
 * @param snapshot 非 Secret Git 摘要
 * @param workspaceGuard 与文件 Tool 共享的真实路径安全边界
 * @since 0.3.0
 */
public record LocalWorkspaceBootstrap(
        List<AgentTool> tools,
        WorkspaceSnapshot snapshot,
        WorkspaceGuard workspaceGuard) {

    /** 冻结 Bootstrap 输出。 */
    public LocalWorkspaceBootstrap {
        tools = List.copyOf(Objects.requireNonNull(tools, "tools 不能为空"));
        snapshot = Objects.requireNonNull(snapshot, "snapshot 不能为空");
        workspaceGuard = Objects.requireNonNull(workspaceGuard, "workspaceGuard 不能为空");
    }

    /**
     * 从真实 Workspace 构造 S03 只读输入与 S04 受控文件写入 Tool。
     *
     * @param workspace Workspace 目录
     * @return 一次性 Bootstrap 快照
     * @throws IOException Workspace 无法解析时
     * @throws WorkspaceAccessException 根 AGENTS.md 存在但违反安全契约时
     */
    public static LocalWorkspaceBootstrap open(Path workspace)
            throws IOException, WorkspaceAccessException {
        WorkspaceGuard guard = new WorkspaceGuard(workspace);
        GitReadClient git = new GitReadClient(guard.workspace());
        // 一个 Workspace 只有一份 Read 证据登记表：read_file 记录的行范围因此成为
        // apply_patch 的写入前置条件，而 Tool 之间仍然互不依赖。
        WorkspaceReadRegistry readRegistry = new WorkspaceReadRegistry();
        ArrayList<AgentTool> tools =
                new ArrayList<>(LocalReadTools.create(guard, readRegistry));
        tools.add(new ApplyPatchTool(guard, readRegistry));
        tools.add(new WriteFileTool(guard, readRegistry));
        tools.add(new RunCommandTool(new LocalCommandExecutor(guard.workspace())));
        return new LocalWorkspaceBootstrap(
                tools,
                WorkspaceSnapshot.capture(git),
                guard);
    }
}
