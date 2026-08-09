package io.github.liumaishenjian.ccjava.cli.subagent;

import io.github.liumaishenjian.ccjava.cli.session.FileCheckpointCoordinator;
import io.github.liumaishenjian.ccjava.cli.session.FileSessionStore;
import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionContextService;
import io.github.liumaishenjian.ccjava.core.subagent.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.subagent.*;
import io.github.liumaishenjian.ccjava.tools.local.LocalWorkspaceBootstrap;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceWriteHardDenial;
import io.github.liumaishenjian.ccjava.tools.local.worktree.LocalGitWorktreeManager;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 为 Headless 父 Session 重新装配独立的子 Session/Context/Permission/Tool Registry。
 *
 * <p>普通 child 使用父 canonical workspace 但重新创建 Workspace Bootstrap，避免 read cache 与 Grant
 * 串扰；worktree child 先通过 fixed-argv Git lease 获得独立 root，再对该 root 完整重装配。每个真实
 * Tool Call 仍进入新 Scope 唯一 {@link ToolExecutionPipeline}，模型循环仍复用 {@link AgentRuntime}。</p>
 *
 * @since 0.12.0
 */
public final class HeadlessChildRuntimeScopeFactory implements ChildRuntimeScopeFactory {
    private final Path parentWorkspace;
    private final Path sessionRoot;
    private final ModelGateway gateway;
    private final ApprovalHandler approvals;
    private final AgentIdGenerator ids;
    private final LifecycleDispatcher lifecycle;
    private final HookCoordinator hooks;
    private final LocalGitWorktreeManager worktrees;
    private final java.util.function.Supplier<AgentSupervisor> supervisor;
    private final java.util.concurrent.ConcurrentMap<DelegationId,
            io.github.liumaishenjian.ccjava.domain.worktree.WorktreeLease> retainedWorktrees =
            new java.util.concurrent.ConcurrentHashMap<>();

    public HeadlessChildRuntimeScopeFactory(Path parentWorkspace, Path sessionRoot, ModelGateway gateway,
            ApprovalHandler approvals, AgentIdGenerator ids, LifecycleDispatcher lifecycle, HookCoordinator hooks) {
        this(parentWorkspace, sessionRoot, gateway, approvals, ids, lifecycle, hooks, () -> null);
    }

    /**
     * 创建可复用父 Supervisor 的 child composition；Supplier 用于打破 composition root 的构造环。
     */
    public HeadlessChildRuntimeScopeFactory(Path parentWorkspace, Path sessionRoot, ModelGateway gateway,
            ApprovalHandler approvals, AgentIdGenerator ids, LifecycleDispatcher lifecycle, HookCoordinator hooks,
            java.util.function.Supplier<AgentSupervisor> supervisor) {
        this.parentWorkspace = Objects.requireNonNull(parentWorkspace).toAbsolutePath().normalize();
        this.sessionRoot = Objects.requireNonNull(sessionRoot).toAbsolutePath().normalize().resolve("subagents");
        this.gateway = Objects.requireNonNull(gateway); this.approvals = Objects.requireNonNull(approvals);
        this.ids = Objects.requireNonNull(ids); this.lifecycle = Objects.requireNonNull(lifecycle);
        this.hooks = Objects.requireNonNull(hooks);
        this.supervisor = Objects.requireNonNull(supervisor);
        LocalGitWorktreeManager available;
        try { available = new LocalGitWorktreeManager(this.parentWorkspace); }
        catch (RuntimeException nonRepositoryOrUnavailableGit) { available = null; }
        this.worktrees = available;
    }

    @Override
    public ChildRuntimeScope create(AgentDefinitionSnapshot definition, ChildTaskRequest request,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(definition); Objects.requireNonNull(request); Objects.requireNonNull(cancellationToken);
        io.github.liumaishenjian.ccjava.domain.worktree.WorktreeLease lease = null;
        Path workspace = parentWorkspace;
        AtomicReference<String> disposition = new AtomicReference<>();
        if (request.worktree()) {
            if (worktrees == null) throw new IllegalStateException("当前 Workspace 不是可验证 Git repository");
            String base = git(parentWorkspace, "rev-parse", "HEAD");
            String slug = request.delegationId().value().replaceAll("[^a-zA-Z0-9-]", "-")
                    .toLowerCase(Locale.ROOT);
            if (slug.length() > 48) slug = slug.substring(0, 48);
            if (!slug.matches("[a-z0-9].*")) slug = "task-" + slug;
            lease = worktrees.create(slug, base);
            workspace = worktrees.enter(lease);
            disposition.set(lease.disposition().name());
        }
        try {
            LocalWorkspaceBootstrap bootstrap = LocalWorkspaceBootstrap.open(workspace);
            Set<String> requested = request.requestedTools().isEmpty()
                    ? definition.visibleTools() : request.requestedTools();
            Set<String> visibleNames = new LinkedHashSet<>(definition.visibleTools());
            visibleNames.retainAll(requested);
            List<AgentTool> availableTools = new ArrayList<>(bootstrap.tools());
            AgentSupervisor sharedSupervisor = supervisor.get();
            // 嵌套 provenance 只能由 Host 从父请求递增；子模型既看不到也不能覆盖 depth。
            if (sharedSupervisor != null) availableTools.add(new DelegateAgentTool(sharedSupervisor, request.depth() + 1));
            List<AgentTool> visible = availableTools.stream()
                    .filter(tool -> visibleNames.contains(tool.definition().name())).toList();
            if (visible.size() != visibleNames.size()) throw new IllegalArgumentException("子 Tool scope 含未注册 Tool");
            ToolRegistry registry = new ToolRegistry(visible);
            InMemorySessionPermissionState permissionState = new InMemorySessionPermissionState();
            PermissionPolicy policy = new PermissionPolicy(definition.permissionCeiling(), List.of(),
                    new DefaultPermissionSelectorResolver(),
                    new DefaultHardDenialPolicy(new WorkspaceWriteHardDenial(bootstrap.workspaceGuard())),
                    permissionState);
            FileSessionStore sessions = new FileSessionStore(sessionRoot, workspace, ids, lifecycle,
                    java.time.Clock.systemUTC(), HookCoordinator.disabled());
            FileCheckpointCoordinator checkpoints = new FileCheckpointCoordinator(sessionRoot,
                    bootstrap.workspaceGuard(), sessions);
            ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry, policy, approvals,
                    permissionState, lifecycle, sessions, checkpoints, hooks);
            AgentSession child = sessions.create(new SessionSpec(definition.instructions(), Map.of(
                    "model", definition.modelName(), "parentVisibility", "bounded-report",
                    "worktree", Boolean.toString(request.worktree()))));
            AgentRuntime runtime = new AgentRuntime(sessions, ids, gateway, new DefaultContextAssembler(),
                    registry, pipeline, lifecycle, sessions, ContextPreparationService.noop(),
                    MemoryContextService.noop(), InstructionContextService.noop(), hooks,
                    io.github.liumaishenjian.ccjava.core.skill.SkillRunCoordinator.disabled(),
                    io.github.liumaishenjian.ccjava.core.plugin.PluginRunCoordinator.disabled(),
                    io.github.liumaishenjian.ccjava.core.plugin.PluginRunHooks.none());
            var capturedLease = lease;
            return new ChildRuntimeScope(runtime, child.id(), () -> {
                try { if (!child.isClosed()) sessions.close(child.id()); } finally {
                    permissionState.clear(child.id()); sessions.close();
                    if (capturedLease != null) {
                        worktrees.leave(capturedLease);
                        // 用户价值默认保留；remove 必须由显式 task.remove 动作触发。
                        var result = worktrees.keep(capturedLease);
                        retainedWorktrees.put(request.delegationId(), result);
                        disposition.set(result.disposition().name());
                    }
                }
            }, () -> Optional.ofNullable(disposition.get()));
        } catch (Exception failure) {
            if (lease != null) {
                try { worktrees.leave(lease); } catch (RuntimeException ignored) { }
                var result = worktrees.removeClean(lease); disposition.set(result.disposition().name());
            }
            throw failure instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("子 Runtime scope 创建失败", failure);
        }
    }

    @Override
    public Optional<String> keepWorktree(DelegationId id) {
        Objects.requireNonNull(id, "delegation id 不能为空");
        var lease = retainedWorktrees.get(id);
        if (lease == null || worktrees == null) return Optional.empty();
        var kept = worktrees.keep(lease);
        retainedWorktrees.put(id, kept);
        return Optional.of(kept.disposition().name());
    }

    @Override
    public Optional<String> removeWorktree(DelegationId id) {
        Objects.requireNonNull(id, "delegation id 不能为空");
        var lease = retainedWorktrees.get(id);
        if (lease == null || worktrees == null) return Optional.empty();
        var result = worktrees.removeClean(lease);
        if (result.disposition() == io.github.liumaishenjian.ccjava.domain.worktree.WorktreeDisposition.REMOVED
                || result.disposition() == io.github.liumaishenjian.ccjava.domain.worktree.WorktreeDisposition.REMOVED_BRANCH_PRESERVED) {
            retainedWorktrees.remove(id, lease);
        } else {
            retainedWorktrees.put(id, result);
        }
        return Optional.of(result.disposition().name());
    }

    private static String git(Path workspace, String... args) {
        List<String> argv = new ArrayList<>(); argv.add("git"); argv.addAll(List.of(args));
        try {
            ProcessBuilder builder = new ProcessBuilder(argv).directory(workspace.toFile()).redirectErrorStream(true);
            Map<String,String> env=builder.environment(); env.put("GIT_TERMINAL_PROMPT","0"); env.put("GCM_INTERACTIVE","Never");
            Process process=builder.start();
            if(!process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)||process.exitValue()!=0)throw new IllegalStateException("Git identity 失败");
            String value=new String(process.getInputStream().readNBytes(129),java.nio.charset.StandardCharsets.UTF_8).trim();
            if(!value.matches("[0-9a-f]{40,64}"))throw new IllegalStateException("Git identity 无效");
            return value;
        } catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw new IllegalStateException("Git identity 失败",failure);}
    }
}
