package io.github.liumaishenjian.ccjava.cli.extensions;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.mcp.McpClientManager;
import io.github.liumaishenjian.ccjava.mcp.McpServerSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * 一个 Headless Session 共享的 Hook 与 MCP 资源快照。
 *
 * <p>Scope 刷新只重用这里的稳定 Tool/Coordinator；资源仅由外层 Session 关闭，避免
 * 活动 Run 捕获旧 Scope 时提前终止 MCP 连接或 Hook 执行器。</p>
 *
 * @since 0.10.0
 */
public final class ExtensionRuntime implements AutoCloseable {
    private final HookCoordinator hooks;
    private final ExecutorService hookExecutor;
    private final McpClientManager mcp;
    private final List<AgentTool> mcpTools;
    private final ExtensionStatus status;

    ExtensionRuntime(HookCoordinator hooks, ExecutorService hookExecutor, McpClientManager mcp,
                     List<AgentTool> mcpTools, ExtensionStatus status) {
        this.hooks = Objects.requireNonNull(hooks, "hooks 不能为空");
        this.hookExecutor = hookExecutor;
        this.mcp = mcp;
        this.mcpTools = List.copyOf(Objects.requireNonNull(mcpTools, "mcpTools 不能为空"));
        this.status = Objects.requireNonNull(status, "status 不能为空");
    }

    /**
     * 返回禁用扩展的安全资源。
     *
     * @return 不含 Hook、MCP Client 或执行器的资源快照
     */
    public static ExtensionRuntime disabled() {
        return new ExtensionRuntime(HookCoordinator.disabled(), null, null, List.of(), ExtensionStatus.empty());
    }

    /**
     * 返回当前 Session 的 Hook 协调器。
     *
     * @return 当前 Session 的 Hook 协调器
     */
    public HookCoordinator hooks() { return hooks; }
    /**
     * 返回已发现并可注册的 MCP Tool 快照。
     *
     * @return MCP Tool 快照
     */
    public List<AgentTool> mcpTools() { return mcpTools; }
    /**
     * 返回不含外部正文的扩展加载状态。
     *
     * @return 扩展加载状态
     */
    public ExtensionStatus status() { return status; }
    /**
     * 返回 MCP Server 的脱敏连接状态。
     *
     * @return MCP Server 状态列表
     */
    public List<McpServerSnapshot> mcpSnapshots() { return mcp == null ? List.of() : mcp.snapshots(); }
    /**
     * 返回显式发现的 Resource/Prompt 元数据目录。
     *
     * @return MCP Context 元数据目录
     */
    public List<io.github.liumaishenjian.ccjava.mcp.McpContextCatalog> mcpContextCatalogs() {
        return mcp == null ? List.of() : mcp.contextCatalogs();
    }

    @Override
    public void close() {
        if (mcp != null) {
            mcp.close();
        }
        if (hookExecutor != null) {
            hookExecutor.shutdownNow();
        }
    }
}
