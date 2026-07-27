package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 驱动单次 Agent Run 中的模型回合与 Tool 回合。
 *
 * <p>该类型是 Core 唯一公开的 Agent Loop 入口。它只负责状态迁移、规范消息
 * 协议、预算和终止判断，不直接访问文件系统、调用终端 UI 或执行具体 Tool。
 * 所有 Tool Call 必须交给 {@link ToolExecutionPipeline}。</p>
 *
 * <p>S01 使用普通同步控制流：追加一条 User Message，请求聚合后的 Model
 * Turn；若无 Tool Call 则完成，否则把 Assistant Message 连同全部调用追加
 * 一次，按顺序得到一一对应的 Tool Result，再请求下一回合。</p>
 *
 * @since 0.1.0
 */
public final class AgentRuntime {

    private final SessionStore sessionStore;
    private final AgentIdGenerator idGenerator;
    private final ModelGateway modelGateway;
    private final ContextAssembler contextAssembler;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionPipeline toolPipeline;
    private final LifecycleDispatcher lifecycle;

    /**
     * 创建显式 Agent Runtime。
     *
     * @param sessionStore    当前进程的 Session Store
     * @param idGenerator     Run ID 来源
     * @param modelGateway    单回合模型端口
     * @param contextAssembler 追加式 Context 组装器
     * @param toolRegistry    当前可见 Tool Registry
     * @param toolPipeline    统一 Tool 执行管线
     * @param lifecycle       生命周期分发器
     */
    public AgentRuntime(
            SessionStore sessionStore,
            AgentIdGenerator idGenerator,
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            ToolRegistry toolRegistry,
            ToolExecutionPipeline toolPipeline,
            LifecycleDispatcher lifecycle) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore 不能为空");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator 不能为空");
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway 不能为空");
        this.contextAssembler = Objects.requireNonNull(
                contextAssembler,
                "contextAssembler 不能为空");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.toolPipeline = Objects.requireNonNull(toolPipeline, "toolPipeline 不能为空");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle 不能为空");
    }

    /**
     * 在已创建的 Session 中执行一条用户消息，直到唯一终态。
     *
     * @param sessionId 目标 Session
     * @param request   用户消息和本次 Run 限制
     * @return Run 终态摘要
     * @throws IllegalArgumentException Session 不存在时抛出
     * @throws IllegalStateException Session 已关闭或已有活动 Run 时抛出
     */
    public AgentRunResult run(SessionId sessionId, AgentRunRequest request) {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Objects.requireNonNull(request, "request 不能为空");
        AgentSession session = sessionStore.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Session 不存在: " + sessionId.value()));
        RunId runId = Objects.requireNonNull(idGenerator.newRunId(), "newRunId 返回 null");
        AgentRunState state = new AgentRunState(sessionId, runId, request.limits());

        session.beginRun(runId, request.userMessage());
        lifecycle.dispatch(session, runId, new LifecycleEvent.RunStarted(request));

        AgentRunResult result;
        try {
            result = executeLoop(session, runId, state);
        } catch (RuntimeException exception) {
            result = state.stop(StopReason.INTERNAL_ERROR);
        }

        session.endRun(runId);
        lifecycle.dispatch(session, runId, new LifecycleEvent.RunFinished(result));
        return result;
    }

    private AgentRunResult executeLoop(
            AgentSession session,
            RunId runId,
            AgentRunState state) {
        while (true) {
            if (!state.canRequestModelTurn()) {
                return state.stop(StopReason.TURN_LIMIT_REACHED);
            }

            int turnNumber = state.recordModelTurnAttempt();
            lifecycle.dispatch(
                    session,
                    runId,
                    new LifecycleEvent.ModelTurnStarted(turnNumber));
            ModelRequest modelRequest = contextAssembler.assemble(
                    session,
                    runId,
                    turnNumber,
                    toolRegistry.definitions());
            ModelTurn modelTurn;
            try {
                modelTurn = modelGateway.complete(modelRequest);
            } catch (ModelGatewayException | RuntimeException exception) {
                return state.stop(StopReason.MODEL_ERROR);
            }
            if (modelTurn == null) {
                return state.stop(StopReason.INVALID_MODEL_RESPONSE);
            }
            lifecycle.dispatch(
                    session,
                    runId,
                    new LifecycleEvent.ModelTurnCompleted(turnNumber, modelTurn));

            AssistantMessage assistant = modelTurn.assistantMessage();
            if (assistant.isEmpty()) {
                return state.stop(StopReason.INVALID_MODEL_RESPONSE);
            }

            List<ToolCall> calls = assistant.toolCalls();
            if (!hasValidToolCallIds(session, calls)) {
                return state.stop(StopReason.INVALID_MODEL_RESPONSE);
            }
            if (calls.isEmpty()) {
                session.appendAssistant(assistant);
                return state.complete(assistant.text());
            }
            if (!state.canAcceptToolBatch(calls.size())) {
                return state.stop(StopReason.TOOL_LIMIT_REACHED);
            }

            session.appendAssistant(assistant);
            for (ToolCall call : calls) {
                int ordinal = state.recordToolCall();
                ToolResult result;
                try {
                    result = toolPipeline.execute(session, runId, ordinal, call);
                } catch (RuntimeException exception) {
                    result = internalToolFailure(call);
                }
                if (!call.id().equals(result.callId())
                        || !call.name().equals(result.toolName())) {
                    result = internalToolFailure(call);
                }
                session.appendToolResult(new ToolResultMessage(result));
            }
        }
    }

    private boolean hasValidToolCallIds(AgentSession session, List<ToolCall> calls) {
        Set<String> batchIds = new HashSet<>();
        for (ToolCall call : calls) {
            if (!batchIds.add(call.id()) || session.hasToolCallId(call.id())) {
                return false;
            }
        }
        return true;
    }

    private ToolResult internalToolFailure(ToolCall call) {
        return ToolResult.failure(
                call.id(),
                call.name(),
                ToolError.of(
                        ToolErrorCode.INTERNAL_ERROR,
                        "Tool Pipeline 发生内部错误"));
    }
}
