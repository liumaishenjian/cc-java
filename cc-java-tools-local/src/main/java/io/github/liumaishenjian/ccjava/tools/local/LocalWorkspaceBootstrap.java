package io.github.liumaishenjian.ccjava.tools.local;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.tools.local.git.GitReadClient;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次性组装 S03 WorkspaceGuard、只读 Tool、根指令和安全 Git 摘要。
 *
 * <p>Composition Root 使用该不可变结果，避免为 Tool、Instructions 和 Snapshot 分别解析
 * Workspace。Bootstrap 不创建 Registry 或 Runtime，也不驱动 Agent Loop。</p>
 *
 * @param tools 按稳定协议顺序排列的五个只读 Tool
 * @param projectInstructions 可选根 AGENTS.md 正文
 * @param snapshot 非 Secret Git 摘要
 * @since 0.3.0
 */
public record LocalWorkspaceBootstrap(
        List<AgentTool> tools,
        Optional<String> projectInstructions,
        WorkspaceSnapshot snapshot) {

    /** 冻结 Bootstrap 输出。 */
    public LocalWorkspaceBootstrap {
        tools = List.copyOf(Objects.requireNonNull(tools, "tools 不能为空"));
        projectInstructions = Objects.requireNonNull(
                projectInstructions, "projectInstructions 不能为空");
        snapshot = Objects.requireNonNull(snapshot, "snapshot 不能为空");
    }

    /**
     * 从真实 Workspace 构造全部 S03 只读输入。
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
        return new LocalWorkspaceBootstrap(
                LocalReadTools.create(guard.workspace()),
                new RootInstructionLoader(guard).load(),
                WorkspaceSnapshot.capture(git));
    }
}
