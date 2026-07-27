package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 统一执行模型提出的每一次 Tool Call。
 *
 * <p>S01 的确定性顺序为：解析 Tool → 参数校验 → Before 事件 → Permission
 * → 可选 Approval → 同步执行 → 规范化 Result → After 事件。未知 Tool、
 * 参数错误和执行异常都转换为带原始 Call ID 的结构化失败结果，使模型可以
 * 在下一回合纠正。输出裁剪、脱敏、超时和取消在后续 Stage 加入同一管线。</p>
 *
 * @since 0.1.0
 */
public final class ToolExecutionPipeline {

    private final ToolRegistry registry;
    private final PermissionGate permissionGate;
    private final ApprovalHandler approvalHandler;
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
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate 不能为空");
        this.approvalHandler = Objects.requireNonNull(
                approvalHandler,
                "approvalHandler 不能为空");
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
        Objects.requireNonNull(session, "session 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(call, "call 不能为空");
        ToolInvocation invocation = new ToolInvocation(session.id(), runId, ordinal, call);

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
        lifecycle.dispatch(
                session,
                runId,
                new LifecycleEvent.PermissionRequested(call, definition.effect()));
        PermissionDecision decision;
        try {
            decision = Objects.requireNonNull(
                    permissionGate.evaluate(invocation, definition),
                    "PermissionGate 返回 null");
            if (decision == PermissionDecision.ASK) {
                decision = Objects.requireNonNull(
                        approvalHandler.requestApproval(invocation, definition),
                        "ApprovalHandler 返回 null");
            }
        } catch (RuntimeException exception) {
            return finish(
                    session,
                    runId,
                    ordinal,
                    ToolResult.failure(
                            call.id(),
                            call.name(),
                            ToolError.of(
                                    ToolErrorCode.INTERNAL_ERROR,
                                    "权限决策失败")));
        }
        if (decision == PermissionDecision.ASK) {
            return finish(
                    session,
                    runId,
                    ordinal,
                    ToolResult.failure(
                            call.id(),
                            call.name(),
                            ToolError.of(
                                    ToolErrorCode.INTERNAL_ERROR,
                                    "ApprovalHandler 必须返回最终 ALLOW 或 DENY")));
        }
        lifecycle.dispatch(
                session,
                runId,
                new LifecycleEvent.PermissionDecided(call, decision));
        if (decision == PermissionDecision.DENY) {
            return finish(
                    session,
                    runId,
                    ordinal,
                    ToolResult.denied(call.id(), call.name(), "Tool 调用未获授权"));
        }

        try {
            ToolExecutionOutcome outcome = Objects.requireNonNull(
                    tool.execute(invocation),
                    "Tool execute 返回 null");
            ToolResult result = outcome.successful()
                    ? ToolResult.success(call.id(), call.name(), outcome.content())
                    : ToolResult.failure(call.id(), call.name(), outcome.error().orElseThrow());
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

    private ToolResult finish(
            AgentSession session,
            RunId runId,
            int ordinal,
            ToolResult result) {
        lifecycle.dispatch(session, runId, new LifecycleEvent.AfterTool(ordinal, result));
        return result;
    }

}
