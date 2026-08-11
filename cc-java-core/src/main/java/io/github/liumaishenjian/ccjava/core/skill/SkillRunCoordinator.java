package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.SessionJournal;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.core.ToolValidationResult;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SkillContextMessage;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.domain.skill.SkillErrorCode;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationKind;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationRequest;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationResult;
import io.github.liumaishenjian.ccjava.domain.skill.SkillProjection;
import io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 管理 Skill 的模型入口、显式入口、Run scoped Projection 与 Tool visibility。
 *
 * <p>所有入口共享 {@link SkillInvoker}。一次成功激活先完成 digest/资源 Gate，再以固定激活顺序
 * 提交 Projection 和 allowlist 交集；失败、取消和重复调用不改变既有 Run scope。模型入口是普通
 * {@link AgentTool}，只能经统一 Tool Pipeline 执行；显式入口由 Application 在创建 Run 前解析，
 * 但仍使用同一激活契约。Run 终态通过 {@link #closeRun(RunId)} exactly-once 清理。</p>
 *
 * @since 0.11.0
 */
public final class SkillRunCoordinator {
    /** 模型入口注册到统一 Tool Pipeline 的稳定名称。 */
    public static final String ACTIVATE_TOOL_NAME = "activate_skill";
    private static final SkillRunCoordinator DISABLED = new SkillRunCoordinator();

    private final SkillCatalog catalog;
    private final SkillInvoker invoker;
    private final List<String> runtimeToolNames;
    private final ConcurrentMap<RunId, RunState> runs = new ConcurrentHashMap<>();
    private final SessionJournal journal;
    private final SkillHookBinder hookBinder;
    private final SkillRecoveryIdentityCatalog recoveryIdentities;
    private final boolean enabled;

    /**
     * 创建启用但不持久化安全事件的 Run 协调器。
     *
     * @param catalog Session 固定 Skill catalog
     * @param invoker 唯一 Skill 激活准备服务
     * @param runtimeToolNames 当前 Runtime Tool 名快照
     */
    public SkillRunCoordinator(SkillCatalog catalog, SkillInvoker invoker, List<String> runtimeToolNames) {
        this(catalog, invoker, runtimeToolNames, SessionJournal.noop(), SkillHookBinder.none(), SkillRecoveryIdentityCatalog.none());
    }

    /**
     * 创建启用且把 Skill started/completed 身份写入 Session journal 的 Run 协调器。
     *
     * @param catalog Session 固定 Skill catalog
     * @param invoker 唯一 Skill 激活准备服务
     * @param runtimeToolNames 当前 Runtime Tool 名快照
     * @param journal 与 Runtime 共用的 durable journal
     */
    public SkillRunCoordinator(SkillCatalog catalog, SkillInvoker invoker, List<String> runtimeToolNames,
            SessionJournal journal) {
        this(catalog, invoker, runtimeToolNames, journal, SkillHookBinder.none(), SkillRecoveryIdentityCatalog.none());
    }

    /**
     * 创建同时在 activation commit 后绑定可信 Skill Hook templates 的协调器。
     *
     * @param catalog Session 固定 Skill catalog
     * @param invoker 唯一 Skill 激活准备服务
     * @param runtimeToolNames 当前 Runtime Tool 名快照
     * @param journal 与 Runtime 共用的 durable journal
     * @param hookBinder 受信 Hook template binder
     */
    public SkillRunCoordinator(SkillCatalog catalog, SkillInvoker invoker, List<String> runtimeToolNames,
            SessionJournal journal, SkillHookBinder hookBinder) {
        this(catalog, invoker, runtimeToolNames, journal, hookBinder, SkillRecoveryIdentityCatalog.none());
    }

    /**
     * 创建同时持有精确 privacy-safe 恢复身份的协调器。
     *
     * @param catalog Session 固定 Skill catalog
     * @param invoker 唯一 Skill 激活准备服务
     * @param runtimeToolNames 当前 Runtime Tool 名快照
     * @param journal 与 Runtime 共用的 durable journal
     * @param hookBinder 受信 Hook template binder
     * @param recoveryIdentities 当前 catalog 的恢复身份目录
     */
    public SkillRunCoordinator(SkillCatalog catalog, SkillInvoker invoker, List<String> runtimeToolNames,
            SessionJournal journal, SkillHookBinder hookBinder,
            SkillRecoveryIdentityCatalog recoveryIdentities) {
        this.catalog = Objects.requireNonNull(catalog, "catalog 不能为空");
        this.invoker = Objects.requireNonNull(invoker, "invoker 不能为空");
        this.runtimeToolNames = List.copyOf(Objects.requireNonNull(runtimeToolNames, "runtimeToolNames 不能为空"));
        if (this.runtimeToolNames.stream().anyMatch(Objects::isNull)) throw new NullPointerException("Tool 名称不能为空");
        this.journal = Objects.requireNonNull(journal, "journal 不能为空");
        this.hookBinder = Objects.requireNonNull(hookBinder, "hookBinder 不能为空");
        this.recoveryIdentities = Objects.requireNonNull(recoveryIdentities, "recoveryIdentities 不能为空");
        this.enabled = true;
    }

    private SkillRunCoordinator() {
        catalog = null;
        invoker = null;
        runtimeToolNames = List.of();
        journal = SessionJournal.noop();
        hookBinder = SkillHookBinder.none();
        recoveryIdentities = SkillRecoveryIdentityCatalog.none();
        enabled = false;
    }

    /**
     * 返回不注入 Skill 或 Tool 的共享兼容实现。
     *
     * @return 禁用 Skill Runtime 的协调器
     */
    public static SkillRunCoordinator disabled() { return DISABLED; }

    /**
     * 返回模型可调用的普通 Tool；catalog 没有 model/both Skill 时为空。
     *
     * @return 必须注册到唯一 Registry/Pipeline 的 Tool
     */
    public Optional<AgentTool> activationTool() {
        if (!enabled || catalog.snapshot().entries().stream().noneMatch(entry -> entry.policy().allowsModel())) {
            return Optional.empty();
        }
        return Optional.of(new ActivationTool());
    }

    /**
     * 在已知 Run 上执行显式调用，供 {@code /skill-name} Application 入口使用。
     *
     * @param request 类型化显式意图
     * @param cancellationToken 当前 Run 取消边界
     * @return 与模型入口相同的激活结果
     */
    public SkillInvocationResult invokeExplicit(SkillInvocationRequest request, CancellationToken cancellationToken) {
        return invokeExplicit(null, request, cancellationToken);
    }

    /**
     * 执行显式调用并在有 Session 身份时写入 durable Skill 事件。
     *
     * @param sessionId 当前 Session；兼容测试可为空并使用 no-op 事件路径
     * @param request 类型化显式意图
     * @param cancellationToken 当前 Run 取消边界
     * @return 与模型入口相同的激活结果
     */
    public SkillInvocationResult invokeExplicit(SessionId sessionId, SkillInvocationRequest request,
            CancellationToken cancellationToken) {
        if (request.kind() != SkillInvocationKind.EXPLICIT) {
            return SkillInvocationResult.failure(SkillErrorCode.INVOCATION_NOT_ALLOWED);
        }
        return invoke(sessionId, request, cancellationToken);
    }

    /**
     * 将已激活 Skill 以固定顺序追加到单回合短生命周期 Context，并收窄 Tool definitions。
     *
     * <p>该方法不修改输入请求或 Session；ContextPreparation 的 compact/recovery 每次均从当前 Run
     * state 重建，因而不会重复激活或注册资源。</p>
     *
     * @param request 尚未发送给 Provider 的请求
     * @return Skill Projection 与可见 Tool 交集后的新请求
     */
    public ModelRequest project(ModelRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        RunState state = runs.get(request.runId());
        if (state == null) return request;
        List<SkillProjection> projections = state.projections();
        if (projections.isEmpty()) return request;
        var messages = new ArrayList<>(request.messages());
        for (SkillProjection projection : projections) {
            messages.add(toMessage(projection, state.kind(projection.content().skillId())));
        }
        LinkedHashSet<String> visible = new LinkedHashSet<>(state.visibleTools());
        visible.add(ACTIVATE_TOOL_NAME);
        return new ModelRequest(request.sessionId(), request.runId(), request.turnNumber(), messages,
                request.toolDefinitions().stream().filter(definition -> visible.contains(definition.name())).toList());
    }

    /**
     * 确定当前 Run 是否允许模型提出指定 Tool。
     *
     * <p>{@code activate_skill} 是宿主控制面入口，在 Run 存续期间保持可见，以允许按稳定顺序
     * 激活多个不同 Skill；其他 Tool 必须属于所有已激活 allowlist 的交集。该判断必须在统一
     * Tool Pipeline 内执行，不能只依赖发给模型的 definitions。</p>
     *
     * @param runId 当前 Run
     * @param toolName 模型提出的 Tool 名
     * @return 未激活 Skill 时为 {@code true}；激活后仅控制面 Tool 或交集内 Tool 为 {@code true}
     */
    public boolean isToolVisible(RunId runId, String toolName) {
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(toolName, "toolName 不能为空");
        RunState state = runs.get(runId);
        return state == null || ACTIVATE_TOOL_NAME.equals(toolName) || state.visibleTools().contains(toolName);
    }

    /**
     * 返回当前 Session Skill activation 使用的真实 Runtime Tool 名快照。
     *
     * @return 不可变 Tool 名列表
     */
    public List<String> runtimeToolNames() { return runtimeToolNames; }

    /**
     * 返回当前 Run 已激活 Skill 的稳定顺序，仅供安全事件/测试观察。
     *
     * @param runId 当前 Run identity
     * @return 激活提交顺序的 Skill ID
     */
    public List<SkillId> activated(RunId runId) {
        RunState state = runs.get(Objects.requireNonNull(runId, "runId 不能为空"));
        return state == null ? List.of() : state.scope.activatedInOrder();
    }

    /**
     * 清理一个 Run 的 Projection、Tool visibility 和激活 guard。
     *
     * @param runId 已到达唯一终态的 Run
     */
    public void closeRun(RunId runId) {
        RunState state = runs.remove(Objects.requireNonNull(runId, "runId 不能为空"));
        if (state != null) state.close();
    }

    private SkillInvocationResult invoke(SessionId sessionId, SkillInvocationRequest request,
            CancellationToken cancellationToken) {
        if (!enabled) return SkillInvocationResult.failure(SkillErrorCode.UNKNOWN_SKILL);
        RunState state = runs.computeIfAbsent(request.runId(), id -> new RunState(id, runtimeToolNames));
        SkillInvocationResult prepared = invoker.invoke(request, state.scope, state.visibleTools(), cancellationToken);
        SkillInvocationResult result = prepared;
        if (prepared.succeeded()) {
            SkillProjection projection = prepared.projection();
            AutoCloseable hookLease = null;
            try {
                if (cancellationToken.isCancellationRequested()) {
                    result = SkillInvocationResult.failure(SkillErrorCode.CANCELLED);
                } else {
                    var descriptor = catalog.find(request.skillId()).orElseThrow();
                    hookLease = hookBinder.bind(request.runId(), descriptor);
                    if (cancellationToken.isCancellationRequested()) {
                        closeLease(hookLease);
                        hookLease = null;
                        result = SkillInvocationResult.failure(SkillErrorCode.CANCELLED);
                    } else {
                        state.commit(projection, request.kind(), hookLease);
                        hookLease = null;
                        if (sessionId != null) {
                            var identity = recoveryIdentities.find(request.skillId())
                                    .orElseThrow(() -> new IllegalStateException("Skill recovery identity 缺失"));
                            journal.skillInvoked(sessionId, request.runId(), request.kind(), new SkillRecoveryRecord(
                                    identity.skillId(), projection.content().snapshotId(), identity.manifestDigest(),
                                    identity.bodyDigest(), identity.contentDigest(), identity.resourcesDigest(),
                                    digestStrings(projection.effectiveVisibleTools()), identity.hookSetDigest(),
                                    identity.pluginTreeDigest(), identity.pluginManifestDigest(),
                                    identity.mcpConfigDigest()));
                        }
                    }
                }
            } catch (RuntimeException failure) {
                closeLease(hookLease);
                state.rollback(request.skillId());
                result = SkillInvocationResult.failure(SkillErrorCode.RESOURCE_REJECTED);
            } finally {
                state.scope.abort(request.skillId());
            }
        }
        if (!result.succeeded() && state.scope.activatedInOrder().isEmpty()) {
            runs.remove(request.runId(), state);
            state.close();
        }
        if (sessionId != null) {
            journal.skillCompleted(sessionId, request.runId(), request.skillId(), request.kind(), result.errorCode());
        }
        return result;
    }

    private static SkillContextMessage toMessage(SkillProjection projection, SkillInvocationKind kind) {
        StringBuilder markdown = new StringBuilder(projection.content().markdown());
        for (var resource : projection.resources()) {
            markdown.append("\n\n--- resource: ").append(resource.logicalName()).append(" ---\n")
                    .append(resource.text());
        }
        return new SkillContextMessage(projection.content().skillId(), projection.content().snapshotId(),
                projection.content().contentDigest(), kind, projection.arguments(), markdown.toString());
    }

    private final class ActivationTool implements AgentTool {
        private final ToolDefinition definition = new ToolDefinition(
                ACTIVATE_TOOL_NAME,
                catalogDescription(),
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"arguments\":{\"type\":\"string\"}},\"required\":[\"name\"],\"additionalProperties\":false}",
                ToolEffect.READ_WORKSPACE, ToolSource.BUILT_IN, true, Duration.ofSeconds(30), "text/plain", 1_024);

        @Override public ToolDefinition definition() { return definition; }

        @Override
        public ToolValidationResult validate(JsonObject arguments) {
            if (arguments.values().keySet().stream().anyMatch(key -> !key.equals("name") && !key.equals("arguments"))) {
                return ToolValidationResult.invalid("activate_skill 参数无效");
            }
            try {
                String name = arguments.string("name").orElse("");
                String text = arguments.string("arguments").orElse("");
                new SkillId(name);
                if (text.codePointCount(0, text.length()) > 8_192) throw new IllegalArgumentException();
                return ToolValidationResult.validResult();
            } catch (IllegalArgumentException failure) {
                return ToolValidationResult.invalid("activate_skill 参数无效");
            }
        }

        @Override
        public ToolExecutionOutcome execute(ToolInvocation invocation) {
            String name = invocation.call().arguments().string("name").orElseThrow();
            String arguments = invocation.call().arguments().string("arguments").orElse("");
            SkillInvocationResult result = invoke(invocation.sessionId(),
                    new SkillInvocationRequest(invocation.runId(), new SkillId(name),
                            SkillInvocationKind.MODEL, arguments), invocation.cancellationToken());
            return result.succeeded()
                    ? ToolExecutionOutcome.success("Skill activated: " + name)
                    : ToolExecutionOutcome.failure(ToolError.of(map(result.errorCode()), "Skill activation rejected"));
        }

        private String catalogDescription() {
            StringBuilder result = new StringBuilder("Activate one workflow for the current run. Available skills:");
            catalog.snapshot().entries().stream().filter(entry -> entry.policy().allowsModel()).forEach(entry -> result
                    .append("\n- ").append(entry.id().value()).append(": ").append(entry.description()));
            return result.toString();
        }
    }

    private static String digestStrings(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            values.stream().sorted().forEach(value -> {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void closeLease(AutoCloseable lease) {
        if (lease == null) return;
        try {
            lease.close();
        } catch (Exception ignored) {
            // lease 关闭必须幂等；清理失败不能用不可信异常替换固定 Skill 终态。
        }
    }

    private static ToolErrorCode map(SkillErrorCode code) {
        return switch (code) {
            case CANCELLED -> ToolErrorCode.OPERATION_CANCELLED;
            case UNKNOWN_SKILL -> ToolErrorCode.INVALID_ARGUMENTS;
            case INVOCATION_NOT_ALLOWED, NESTED_INVOCATION, ALREADY_ACTIVATED -> ToolErrorCode.PERMISSION_DENIED;
            default -> ToolErrorCode.EXECUTION_FAILED;
        };
    }

    private static final class RunState implements AutoCloseable {
        private final SkillScope scope;
        private final List<SkillProjection> projections = new ArrayList<>();
        private final Map<SkillId, SkillInvocationKind> kinds = new LinkedHashMap<>();
        private final List<AutoCloseable> hookLeases = new ArrayList<>();
        private final List<List<String>> visibilityHistory = new ArrayList<>();
        private List<String> visibleTools;

        private RunState(RunId runId, List<String> runtimeTools) {
            scope = new SkillScope(runId);
            visibleTools = List.copyOf(runtimeTools);
        }

        private synchronized void commit(SkillProjection projection, SkillInvocationKind kind,
                AutoCloseable hookLease) {
            Objects.requireNonNull(projection, "projection 不能为空");
            Objects.requireNonNull(kind, "kind 不能为空");
            Objects.requireNonNull(hookLease, "hookLease 不能为空");
            visibilityHistory.add(visibleTools);
            projections.add(projection);
            kinds.put(projection.content().skillId(), kind);
            hookLeases.add(hookLease);
            visibleTools = projection.effectiveVisibleTools();
            scope.commit(projection.content().skillId());
        }

        private synchronized void rollback(SkillId id) {
            if (projections.isEmpty() || !projections.getLast().content().skillId().equals(id)) return;
            projections.removeLast();
            kinds.remove(id);
            visibleTools = visibilityHistory.removeLast();
            closeLease(hookLeases.removeLast());
            scope.rollbackLast(id);
        }

        private synchronized List<SkillProjection> projections() { return List.copyOf(projections); }
        private synchronized List<String> visibleTools() { return visibleTools; }
        private synchronized SkillInvocationKind kind(SkillId id) { return kinds.get(id); }
        @Override public synchronized void close() {
            for (int index = hookLeases.size() - 1; index >= 0; index--) closeLease(hookLeases.get(index));
            hookLeases.clear();
            visibilityHistory.clear();
            projections.clear();
            kinds.clear();
            visibleTools = List.of();
            scope.close();
        }
    }
}
