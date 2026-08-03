package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.PermissionReason;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 统一执行模型提出的每一次 Tool Call。
 *
 * <p>确定性顺序为：解析 Tool → 参数校验 → Before 事件 → Permission
 * → 可选 Approval → 同步执行 → 规范化/最终裁剪 Result → After 事件。未知 Tool、
 * 参数错误和执行异常都转换为带原始 Call ID 的结构化失败结果，使模型可以
 * 在下一回合纠正。S03 在这里强制最终字符 ceiling，确保 Tool、事件、Session History
 * 和下一模型回合看不到裁剪前旁路结果；超时和取消仍由后续 Stage 加入同一管线。</p>
 *
 * @since 0.1.0
 */
public final class ToolExecutionPipeline {

    /** 无论 Tool Definition 如何声明，Pipeline 都不会向 Context 放入更多字符。 */
    public static final int ABSOLUTE_MAX_OUTPUT_CHARACTERS = 64_000;

    private static final String TRUNCATION_MARKER = "\n[truncated: pipeline character limit]";

    private final ToolRegistry registry;
    private final PermissionGate permissionGate;
    private final ApprovalHandler approvalHandler;
    private final SessionPermissionState permissionState;
    private final LifecycleDispatcher lifecycle;

    /**
     * 创建 Tool 执行管线。
     *
     * @param registry        唯一 Tool Registry
     * @param permissionGate  最小权限决策端口
     * @param approvalHandler ASK 决策的审批端口
     * @param lifecycle       生命周期分发器
     */
    public ToolExecutionPipeline(
            ToolRegistry registry,
            PermissionGate permissionGate,
            ApprovalHandler approvalHandler,
            LifecycleDispatcher lifecycle) {
        this(
                registry,
                permissionGate,
                approvalHandler,
                new InMemorySessionPermissionState(),
                lifecycle);
    }

    /**
     * 创建共享 S05 Session Permission 状态的 Tool 管线。
     *
     * @param registry 唯一 Tool Registry
     * @param permissionGate 类型化 Policy Kernel
     * @param approvalHandler ASK 审批端口
     * @param permissionState 与 Policy 共享的当前 Session 内存状态
     * @param lifecycle 生命周期分发器
     */
    public ToolExecutionPipeline(
            ToolRegistry registry,
            PermissionGate permissionGate,
            ApprovalHandler approvalHandler,
            SessionPermissionState permissionState,
            LifecycleDispatcher lifecycle) {
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate 不能为空");
        this.approvalHandler = Objects.requireNonNull(
                approvalHandler,
                "approvalHandler 不能为空");
        this.permissionState = Objects.requireNonNull(
                permissionState,
                "permissionState 不能为空");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle 不能为空");
    }

    /**
     * 顺序处理一次 Tool Call，并保证结果 ID 与原始调用一致。
     *
     * @param session 当前 Session
     * @param runId   当前 Run
     * @param ordinal 本次 Run 内的调用序号
     * @param call    原始模型调用
     * @return 已规范化结果
     */
    public ToolResult execute(
            AgentSession session,
            RunId runId,
            int ordinal,
            ToolCall call) {
        return execute(session, runId, ordinal, call, CancellationToken.none());
    }

    /**
     * 顺序处理一次 Tool Call，并把当前 Run 的取消信号传播给 Tool Adapter。
     *
     * <p>取消信号只允许 Adapter 终止自身 I/O 或子进程；Run 的最终
     * {@code USER_CANCELLED} 状态仍由 {@link AgentRuntime} 唯一决定。</p>
     *
     * @param session 当前 Session
     * @param runId 当前 Run
     * @param ordinal 本次 Run 内的调用序号
     * @param call 原始模型调用
     * @param cancellationToken 当前 Run 的取消信号
     * @return 已规范化结果
     */
    public ToolResult execute(
            AgentSession session,
            RunId runId,
            int ordinal,
            ToolCall call,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(session, "session 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(call, "call 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        ToolInvocation invocation = new ToolInvocation(
                session.id(),
                runId,
                ordinal,
                call,
                cancellationToken,
                (stream, text) -> publishToolOutput(
                        session, runId, ordinal, call.name(), stream, text));

        AgentTool tool = registry.find(call.name()).orElse(null);
        if (tool == null) {
            return finish(
                    session,
                    runId,
                    ordinal,
                    ToolResult.failure(
                            call.id(),
                            call.name(),
                            ToolError.of(
                                    ToolErrorCode.UNKNOWN_TOOL,
                                    "未注册 Tool: " + call.name())));
        }

        ToolValidationResult validation;
        try {
            validation = Objects.requireNonNull(
                    tool.validate(call.arguments()),
                    "Tool validate 返回 null");
        } catch (RuntimeException exception) {
            validation = ToolValidationResult.invalid(
                    "参数校验器发生异常");
        }
        if (!validation.valid()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("violations", validation.violations());
            return finish(
                    session,
                    runId,
                    ordinal,
                    ToolResult.failure(
                            call.id(),
                            call.name(),
                            new ToolError(
                                    ToolErrorCode.INVALID_ARGUMENTS,
                                    "Tool 参数校验失败",
                            new JsonObject(details))));
        }

        lifecycle.dispatch(session, runId, new LifecycleEvent.BeforeTool(ordinal, call));
        ToolDefinition definition = tool.definition();
        LifecycleEvent.PermissionCallSummary permissionCall = permissionCall(call, definition);
        lifecycle.dispatch(
                session,
                runId,
                new LifecycleEvent.PermissionEvaluationStarted(permissionCall));
        PermissionOutcome outcome;
        try {
            outcome = Objects.requireNonNull(
                    permissionGate.evaluate(invocation, definition),
                    "PermissionGate 返回 null");
        } catch (RuntimeException exception) {
            outcome = policyFailureOutcome(call, definition);
        }
        lifecycle.dispatch(
                session,
                runId,
                new LifecycleEvent.PermissionEvaluated(
                        permissionCall,
                        permissionSummary(outcome, outcome.decision() == PermissionDecision.ASK)));
        if (outcome.decision() == PermissionDecision.ASK) {
            if (permissionState.denialCount(session.id(), outcome.selector()) >= 2) {
                outcome = PermissionOutcome.of(
                        PermissionDecision.DENY,
                        PermissionReason.REPEATED_DENIAL,
                        outcome.selector());
            } else {
                lifecycle.dispatch(
                        session,
                        runId,
                        new LifecycleEvent.ApprovalRequested(
                                permissionCall,
                                permissionSummary(outcome, true)));
                outcome = requestApprovalFailClosed(
                        session, invocation, definition, outcome);
            }
        }
        lifecycle.dispatch(
                session,
                runId,
                new LifecycleEvent.PermissionDecided(
                        permissionCall,
                        permissionSummary(outcome, false)));
        if (outcome.decision() == PermissionDecision.DENY) {
            if (outcome.reason() != PermissionReason.POLICY_EVALUATION_FAILED_CLOSED) {
                permissionState.recordDenialUpTo(session.id(), outcome.selector(), 3);
            }
            return finish(
                    session,
                    runId,
                    ordinal,
                    ToolResult.denied(call.id(), call.name(), "Tool 调用未获授权"));
        }

        PermissionOutcome finalOutcome = outcome;
        try {
            ToolExecutionOutcome execution = Objects.requireNonNull(
                    tool.execute(invocation),
                    "Tool execute 返回 null");
            ToolResult result = execution.successful()
                    ? normalizeSuccess(call, definition, execution)
                    : ToolResult.failure(call.id(), call.name(), execution.error().orElseThrow());
            if (result.status() == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS) {
                permissionState.clearDenials(session.id(), finalOutcome.selector());
            }
            return finish(session, runId, ordinal, result);
        } catch (Exception exception) {
            return finish(
                    session,
                    runId,
                    ordinal,
                    ToolResult.failure(
                            call.id(),
                            call.name(),
                            ToolError.of(
                                    ToolErrorCode.EXECUTION_FAILED,
                                    "Tool 执行失败")));
        }
    }

    private static PermissionOutcome policyFailureOutcome(
            ToolCall call,
            ToolDefinition definition) {
        return PermissionOutcome.of(
                PermissionDecision.DENY,
                PermissionReason.POLICY_EVALUATION_FAILED_CLOSED,
                io.github.liumaishenjian.ccjava.domain.PermissionSelector.toolWide(
                        call.name(), definition.source()));
    }

    private static LifecycleEvent.PermissionCallSummary permissionCall(
            ToolCall call,
            ToolDefinition definition) {
        return new LifecycleEvent.PermissionCallSummary(
                call.id(),
                call.name(),
                definition.effect());
    }

    private static LifecycleEvent.PermissionDecisionSummary permissionSummary(
            PermissionOutcome outcome,
            boolean interactive) {
        return new LifecycleEvent.PermissionDecisionSummary(
                outcome.decision(),
                outcome.reason(),
                outcome.ruleSource(),
                interactive,
                !outcome.selector().toolWide());
    }

    private PermissionOutcome requestApprovalFailClosed(
            AgentSession session,
            ToolInvocation invocation,
            ToolDefinition definition,
            PermissionOutcome initial) {
        try {
            ApprovalResponse response = Objects.requireNonNull(
                    approvalHandler.requestApproval(invocation, definition, initial),
                    "ApprovalHandler 返回 null");
            return resolveApproval(session, initial, response);
        } catch (RuntimeException exception) {
            return PermissionOutcome.of(
                    PermissionDecision.DENY,
                    PermissionReason.APPROVAL_FAILED_CLOSED,
                    initial.selector());
        }
    }

    private PermissionOutcome resolveApproval(
            AgentSession session,
            PermissionOutcome initial,
            ApprovalResponse response) {
        return switch (response.action()) {
            case ALLOW_ONCE -> PermissionOutcome.of(
                    PermissionDecision.ALLOW,
                    PermissionReason.USER_ALLOW_ONCE,
                    initial.selector());
            case ALLOW_SESSION -> {
                var scope = response.scope().orElseThrow();
                if (!scope.equals(initial.selector()) || scope.toolWide()) {
                    throw new IllegalArgumentException("Session approval scope 与请求不匹配");
                }
                permissionState.grant(session.id(), scope);
                yield PermissionOutcome.of(
                        PermissionDecision.ALLOW,
                        PermissionReason.USER_ALLOW_SESSION,
                        initial.selector());
            }
            case DENY -> PermissionOutcome.of(
                    PermissionDecision.DENY,
                    PermissionReason.USER_DENY,
                    initial.selector());
        };
    }

    private ToolResult normalizeSuccess(
            ToolCall call,
            ToolDefinition definition,
            ToolExecutionOutcome outcome) {
        int limit = Math.min(
                definition.maxOutputCharacters(),
                ABSOLUTE_MAX_OUTPUT_CHARACTERS);
        String original = outcome.content();
        int originalCharacters = original.codePointCount(0, original.length());
        if (originalCharacters <= limit) {
            return ToolResult.success(
                    call.id(),
                    call.name(),
                    original,
                    outcome.metadata().normalize(original, false, originalCharacters));
        }

        int markerCharacters = TRUNCATION_MARKER.codePointCount(0, TRUNCATION_MARKER.length());
        String normalized;
        if (limit <= markerCharacters) {
            normalized = prefixByCodePoints(TRUNCATION_MARKER, limit);
        } else {
            normalized = prefixByCodePoints(original, limit - markerCharacters)
                    + TRUNCATION_MARKER;
        }
        return ToolResult.success(
                call.id(),
                call.name(),
                normalized,
                outcome.metadata().normalize(normalized, true, originalCharacters));
    }

    private static String prefixByCodePoints(String value, int codePoints) {
        if (codePoints == 0) {
            return "";
        }
        int end = value.offsetByCodePoints(0, codePoints);
        return value.substring(0, end);
    }

    private ToolResult finish(
            AgentSession session,
            RunId runId,
            int ordinal,
            ToolResult result) {
        lifecycle.dispatch(session, runId, new LifecycleEvent.AfterTool(ordinal, result));
        return result;
    }

    private void publishToolOutput(
            AgentSession session,
            RunId runId,
            int ordinal,
            String toolName,
            ToolOutputStream stream,
            String text) {
        try {
            lifecycle.dispatch(
                    session,
                    runId,
                    new LifecycleEvent.ToolOutput(ordinal, toolName, stream, text));
        } catch (RuntimeException ignored) {
            // 输出事件是只读旁路，Surface 失败不能改变 Tool 的权威执行结果。
        }
    }

}
