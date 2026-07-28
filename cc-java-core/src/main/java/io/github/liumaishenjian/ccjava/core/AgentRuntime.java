package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTextDelta;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 驱动单次 Agent Run 中的模型回合与 Tool 回合。
 *
 * <p>该类型是 Core 唯一公开的 Agent Loop 入口。它只负责状态迁移、规范消息
 * 协议、预算和终止判断，不直接访问文件系统、调用终端 UI 或执行具体 Tool。
 * 所有 Tool Call 必须交给 {@link ToolExecutionPipeline}。</p>
 *
 * <p>Runtime 始终使用普通同步控制流：追加一条 User Message，请求聚合后的
 * Model Turn；S02 Adapter 可以在该同步调用期间通过项目 Observer 发布文本增量，
 * 但最终仍须返回完整 Turn。若无 Tool Call 则完成，否则把 Assistant Message
 * 连同全部调用追加一次，按顺序得到一一对应的 Tool Result，再请求下一回合。</p>
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
    private final Clock clock;

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
        this(
                sessionStore,
                idGenerator,
                modelGateway,
                contextAssembler,
                toolRegistry,
                toolPipeline,
                lifecycle,
                Clock.systemUTC());
    }

    /**
     * 使用确定性时间来源创建显式 Agent Runtime。
     *
     * @param sessionStore 当前进程的 Session Store
     * @param idGenerator Run ID 来源
     * @param modelGateway 单回合模型端口
     * @param contextAssembler 追加式 Context 组装器
     * @param toolRegistry 当前可见 Tool Registry
     * @param toolPipeline 统一 Tool 执行管线
     * @param lifecycle 生命周期分发器
     * @param clock Deadline 判断的时间来源
     */
    public AgentRuntime(
            SessionStore sessionStore,
            AgentIdGenerator idGenerator,
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            ToolRegistry toolRegistry,
            ToolExecutionPipeline toolPipeline,
            LifecycleDispatcher lifecycle,
            Clock clock) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore 不能为空");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator 不能为空");
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway 不能为空");
        this.contextAssembler = Objects.requireNonNull(
                contextAssembler,
                "contextAssembler 不能为空");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.toolPipeline = Objects.requireNonNull(toolPipeline, "toolPipeline 不能为空");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
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
        return run(sessionId, request, CancellationToken.none());
    }

    /**
     * 在可由外层触发的取消边界内执行一条用户消息。
     *
     * <p>取消只中断 S02 模型流。Tool 或子进程取消在 S04 进入同一 Token
     * 传播路径；已经开始的 S01 顺序 Tool 批次仍会完成协议配对。</p>
     *
     * @param sessionId 目标 Session
     * @param request 用户消息和本次 Run 限制
     * @param cancellationToken 当前 Run 的取消信号
     * @return Run 终态摘要
     */
    public AgentRunResult run(
            SessionId sessionId,
            AgentRunRequest request,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        AgentSession session = sessionStore.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Session 不存在: " + sessionId.value()));
        RunId runId = Objects.requireNonNull(idGenerator.newRunId(), "newRunId 返回 null");
        AgentRunState state = new AgentRunState(
                sessionId,
                runId,
                request.limits(),
                clock.instant());

        session.beginRun(runId, request.userMessage());

        AgentRunResult result;
        try {
            lifecycle.dispatch(session, runId, new LifecycleEvent.RunStarted(request));
            result = executeLoop(
                    session,
                    runId,
                    state,
                    cancellationToken,
                    request.limits().maxModelRetries());
        } catch (RuntimeException exception) {
            result = state.stop(StopReason.INTERNAL_ERROR);
        } finally {
            session.endRun(runId);
        }

        lifecycle.dispatch(session, runId, new LifecycleEvent.RunFinished(result));
        return result;
    }

    private AgentRunResult executeLoop(
            AgentSession session,
            RunId runId,
            AgentRunState state,
            CancellationToken cancellationToken,
            int maxModelRetries) {
        while (true) {
            if (cancellationToken.isCancellationRequested()) {
                return state.stop(StopReason.USER_CANCELLED);
            }
            if (state.deadlineReached(clock.instant())) {
                return state.stop(StopReason.TIME_LIMIT_REACHED);
            }
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
            AtomicBoolean visibleTextPublished = new AtomicBoolean();
            ModelTurnObserver observer = text -> publishTextDelta(
                    session,
                    runId,
                    state,
                    cancellationToken,
                    turnNumber,
                    text,
                    visibleTextPublished);
            ModelTurn modelTurn;
            try {
                modelTurn = requestModelTurn(
                        modelRequest,
                        observer,
                        cancellationToken,
                        state,
                        maxModelRetries,
                        visibleTextPublished);
            } catch (ModelGatewayException exception) {
                if (cancellationToken.isCancellationRequested()
                        || exception.kind() == ModelFailureKind.CANCELLED) {
                    return state.stop(StopReason.USER_CANCELLED);
                }
                if (state.deadlineReached(clock.instant())
                        || exception.kind() == ModelFailureKind.DEADLINE_EXCEEDED) {
                    return state.stop(StopReason.TIME_LIMIT_REACHED);
                }
                if (exception.kind() == ModelFailureKind.RESPONSE_LIMIT_EXCEEDED) {
                    return state.stop(StopReason.MODEL_OUTPUT_LIMIT_REACHED);
                }
                return state.stop(StopReason.MODEL_ERROR);
            }
            if (cancellationToken.isCancellationRequested()) {
                return state.stop(StopReason.USER_CANCELLED);
            }
            if (state.deadlineReached(clock.instant())) {
                return state.stop(StopReason.TIME_LIMIT_REACHED);
            }
            if (modelTurn == null) {
                return state.stop(StopReason.INVALID_MODEL_RESPONSE);
            }
            state.recordCompletedModelTurn(modelTurn);
            boolean completionPublished = cancellationToken.runIfActive(() ->
                    lifecycle.dispatch(
                            session,
                            runId,
                            new LifecycleEvent.ModelTurnCompleted(turnNumber, modelTurn)));
            if (!completionPublished) {
                return state.stop(StopReason.USER_CANCELLED);
            }

            if (modelTurn.finishReason() == ModelFinishReason.LENGTH) {
                return state.stop(StopReason.MODEL_OUTPUT_LIMIT_REACHED);
            }
            if (modelTurn.finishReason() == ModelFinishReason.CONTENT_FILTER) {
                return state.stop(StopReason.INVALID_MODEL_RESPONSE);
            }

            AssistantMessage assistant = modelTurn.assistantMessage();
            if (assistant.isEmpty()) {
                return state.stop(StopReason.INVALID_MODEL_RESPONSE);
            }

            List<ToolCall> calls = assistant.toolCalls();
            if (!hasValidToolCallIds(session, calls)) {
                return state.stop(StopReason.INVALID_MODEL_RESPONSE);
            }
            if (calls.isEmpty()) {
                AtomicReference<AgentRunResult> completed = new AtomicReference<>();
                boolean completionAccepted = cancellationToken.runIfActive(() -> {
                    session.appendAssistant(assistant);
                    completed.set(state.complete(assistant.text()));
                });
                return completionAccepted
                        ? completed.get()
                        : state.stop(StopReason.USER_CANCELLED);
            }
            if (!state.canAcceptToolBatch(calls.size())) {
                return state.stop(StopReason.TOOL_LIMIT_REACHED);
            }

            if (!cancellationToken.runIfActive(() -> session.appendAssistant(assistant))) {
                return state.stop(StopReason.USER_CANCELLED);
            }
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

    private ModelTurn requestModelTurn(
            ModelRequest request,
            ModelTurnObserver observer,
            CancellationToken cancellationToken,
            AgentRunState state,
            int maxRetries,
            AtomicBoolean visibleTextPublished) throws ModelGatewayException {
        int retries = 0;
        while (true) {
            if (cancellationToken.isCancellationRequested()) {
                throw ModelGatewayException.cancelled("模型请求已取消");
            }
            if (state.deadlineReached(clock.instant())) {
                throw ModelGatewayException.deadlineExceeded("模型请求超过截止时间");
            }
            AttemptObserver attemptObserver = new AttemptObserver(observer);
            ModelCallContext callContext = new ModelCallContext(
                    attemptObserver,
                    cancellationToken,
                    Optional.of(state.deadline()),
                    retries + 1);
            try {
                return modelGateway.complete(request, callContext);
            } catch (ModelGatewayException exception) {
                if (cancellationToken.isCancellationRequested()) {
                    throw ModelGatewayException.cancelled("模型请求已取消");
                }
                if (state.deadlineReached(clock.instant())) {
                    throw ModelGatewayException.deadlineExceeded("模型请求超过截止时间");
                }
                boolean canRetry = exception.retryable()
                        && !exception.partialResponse()
                        && !visibleTextPublished.get()
                        && retries < maxRetries;
                if (!canRetry) {
                    throw exception;
                }
                retries++;
            } catch (RuntimeException exception) {
                throw new ModelGatewayException(
                        ModelFailureKind.UNKNOWN,
                        "模型 Adapter 发生未分类错误",
                        false,
                        visibleTextPublished.get(),
                        exception);
            } finally {
                attemptObserver.close();
            }
        }
    }

    private void publishTextDelta(
            AgentSession session,
            RunId runId,
            AgentRunState state,
            CancellationToken cancellationToken,
            int turnNumber,
            String text,
            AtomicBoolean visibleTextPublished) {
        Objects.requireNonNull(text, "Model text delta 不能为空");
        if (text.isEmpty() || state.deadlineReached(clock.instant())) {
            return;
        }
        cancellationToken.runIfActive(() -> {
            if (state.deadlineReached(clock.instant())) {
                return;
            }
            visibleTextPublished.set(true);
            lifecycle.dispatch(
                    session,
                    runId,
                    new ModelTextDelta(turnNumber, text));
        });
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

    /**
     * 在单次 Provider 尝试返回或失败后拒绝迟到的流事件。
     *
     * <p>关闭与发布共享同一监视器，因此 {@link #close()} 返回后不会再开始
     * 调用下游 Observer。真实 Adapter 仍应在返回前终止底层订阅；该门只负责
     * 保护 Runtime 事件事实。</p>
     */
    private static final class AttemptObserver implements ModelTurnObserver, AutoCloseable {

        private final ModelTurnObserver delegate;
        private boolean open = true;

        private AttemptObserver(ModelTurnObserver delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate 不能为空");
        }

        @Override
        public synchronized void onTextDelta(String text) {
            if (open) {
                delegate.onTextDelta(text);
            }
        }

        @Override
        public synchronized void close() {
            open = false;
        }
    }
}
