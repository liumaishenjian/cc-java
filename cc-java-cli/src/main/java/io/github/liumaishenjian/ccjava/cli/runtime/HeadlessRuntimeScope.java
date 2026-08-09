package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.core.AgentIdGenerator;
import io.github.liumaishenjian.ccjava.core.AgentRuntime;
import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.ApprovalHandler;
import io.github.liumaishenjian.ccjava.core.ContextPreparationService;
import io.github.liumaishenjian.ccjava.core.DefaultContextAssembler;
import io.github.liumaishenjian.ccjava.core.DefaultHardDenialPolicy;
import io.github.liumaishenjian.ccjava.core.DefaultPermissionSelectorResolver;
import io.github.liumaishenjian.ccjava.core.InMemorySessionPermissionState;
import io.github.liumaishenjian.ccjava.core.LifecycleDispatcher;
import io.github.liumaishenjian.ccjava.core.MemoryContextService;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.core.PermissionPolicy;
import io.github.liumaishenjian.ccjava.core.ToolExecutionPipeline;
import io.github.liumaishenjian.ccjava.core.ToolRegistry;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionContextService;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.cli.session.FileCheckpointCoordinator;
import io.github.liumaishenjian.ccjava.cli.session.FileSessionStore;
import io.github.liumaishenjian.ccjava.domain.PermissionRuleSource;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.domain.settings.RuntimeConfiguration;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceWriteHardDenial;
import java.util.List;
import java.util.Objects;

/**
 * 一次 Run 捕获的完整、不可变运行装配。
 *
 * <p>Scope 只持有 Settings 可替换的模型、可见 builtin Tool、S05 Policy、Pipeline 与 Runtime。
 * Session、durable writer、审批、权限 Grant、Instructions、Memory 和 Lifecycle 由外层 Session 共享；
 * 因此旧 Scope 在已捕获 Run 结束前无需关闭，也不会释放会话级资源。</p>
 *
 * @since 0.8.0
 */
final class HeadlessRuntimeScope {
    private final AgentRuntime runtime;
    private final RuntimeConfiguration configuration;

    private HeadlessRuntimeScope(AgentRuntime runtime, RuntimeConfiguration configuration) {
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为空");
        this.configuration = Objects.requireNonNull(configuration, "configuration 不能为空");
    }

    AgentRuntime runtime() {
        return runtime;
    }

    RuntimeConfiguration configuration() {
        return configuration;
    }

    /**
     * 从已验证的 RuntimeConfiguration 构造下一 Run 的完整依赖图。
     *
     * <p>当前 Provider seam 只接受启动时已装配的模型名；后续 Provider adapter 可以在此处
     * 依据非持久化凭证创建候选 Gateway，但不得把凭证放入 RuntimeConfiguration 或诊断。</p>
     */
    static HeadlessRuntimeScope create(
            RuntimeConfiguration configuration,
            String configuredModel,
            ModelGateway gateway,
            ContextPreparationService contextPreparation,
            List<AgentTool> registeredTools,
            FileSessionStore sessions,
            FileCheckpointCoordinator checkpoints,
            LifecycleDispatcher lifecycle,
            AgentIdGenerator ids,
            ApprovalHandler approvals,
            InMemorySessionPermissionState permissionState,
            io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard workspaceGuard,
            MemoryContextService memoryContext,
            InstructionContextService instructionContext,
            HookCoordinator hooks,
            io.github.liumaishenjian.ccjava.core.skill.SkillRunCoordinator skills,
            io.github.liumaishenjian.ccjava.core.plugin.PluginRunCoordinator plugins,
            io.github.liumaishenjian.ccjava.core.plugin.PluginRunHooks pluginHooks) {
        RuntimeConfiguration checkedConfiguration = Objects.requireNonNull(configuration, "configuration 不能为空");
        if (checkedConfiguration.modelName().isPresent()
                && !checkedConfiguration.modelName().orElseThrow().equals(configuredModel)) {
            throw new IllegalArgumentException("当前 Provider 不支持该模型");
        }
        if (checkedConfiguration.permissionRules().stream()
                .anyMatch(rule -> rule.source() != PermissionRuleSource.STARTUP)) {
            throw new IllegalArgumentException("RuntimeConfiguration 只能包含 STARTUP 规则");
        }
        if (!checkedConfiguration.toolConfigurations().isEmpty()) {
            throw new IllegalArgumentException("当前 builtin Tool 不支持 Runtime 配置");
        }
        List<AgentTool> visibleBuiltins = registeredTools.stream()
                .filter(tool -> tool.definition().source() == ToolSource.BUILT_IN)
                .filter(tool -> checkedConfiguration.enabledBuiltinTools().contains(tool.definition().name()))
                .toList();
        if (visibleBuiltins.size() != checkedConfiguration.enabledBuiltinTools().size()) {
            throw new IllegalArgumentException("RuntimeConfiguration 含未注册 builtin Tool");
        }
        List<AgentTool> visibleTools = java.util.stream.Stream.concat(
                visibleBuiltins.stream(),
                registeredTools.stream().filter(tool -> tool.definition().source() != ToolSource.BUILT_IN))
                .toList();
        ToolRegistry registry = new ToolRegistry(visibleTools);
        PermissionPolicy policy = new PermissionPolicy(
                checkedConfiguration.permissionMode(),
                checkedConfiguration.permissionRules(),
                new DefaultPermissionSelectorResolver(),
                new DefaultHardDenialPolicy(new WorkspaceWriteHardDenial(workspaceGuard)),
                permissionState);
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                registry, policy, approvals, permissionState, lifecycle, sessions, checkpoints, hooks, skills);
        return new HeadlessRuntimeScope(new AgentRuntime(
                sessions, ids, gateway, new DefaultContextAssembler(), registry, pipeline, lifecycle, sessions,
                contextPreparation, memoryContext, instructionContext, hooks, skills, plugins, pluginHooks),
                checkedConfiguration);
    }
}
