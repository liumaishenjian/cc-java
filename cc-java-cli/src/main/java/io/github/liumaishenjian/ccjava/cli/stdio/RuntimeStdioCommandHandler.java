package io.github.liumaishenjian.ccjava.cli.stdio;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.ModelTurnTelemetry;
import io.github.liumaishenjian.ccjava.core.RunTelemetry;
import io.github.liumaishenjian.ccjava.core.ToolCallTelemetry;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.cli.session.SessionStorage;
import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.ModelTextDelta;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.model.springai.config.OpenAiCompatibleSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 把 stdio v0 命令适配到真实 {@link HeadlessRuntimeSession}。
 *
 * <p>该类型只管理单连接的 Session/Run 状态和事件映射。模型循环、规范消息历史、
 * Tool Pipeline、取消与终态仍由 Core 拥有；S03 只把 Core Lifecycle 投影为不含参数、正文、
 * 绝对路径和原始异常的 Tool 进度事件。</p>
 *
 * @since 0.1.0
 */
public final class RuntimeStdioCommandHandler
        implements StdioProtocol.CommandHandler, AgentEventSink {

    private final Object lock = new Object();
    private final StdioProtocolCodec codec = new StdioProtocolCodec();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("cc-java-runtime-run").daemon(true).factory());
    private final StdioApprovalCoordinator approvals;
    private final HeadlessRuntimeSession application;
    private State state = State.NEW;
    private ActiveRun activeRun;

    /**
     * 使用已校验的本地 Provider 设置装配 Headless Runtime。
     *
     * @param settings 不得记录或持久化的 Provider 设置
     */
    public RuntimeStdioCommandHandler(OpenAiCompatibleSettings settings) {
        this(
                settings,
                Path.of("").toAbsolutePath().normalize(),
                io.github.liumaishenjian.ccjava.domain.AgentLimits.DEFAULT.maxDuration());
    }

    /**
     * 使用 CLI 已解析的 Workspace 与墙钟限制装配 Headless Runtime。
     *
     * @param settings 已应用模型覆盖的 Provider 设置
     * @param workspace 已解析的真实 Workspace
     * @param timeout 每个 Run 的墙钟限制
     */
    public RuntimeStdioCommandHandler(
            OpenAiCompatibleSettings settings,
            Path workspace,
            Duration timeout) {
        this(settings, workspace, timeout, PermissionMode.DEFAULT);
    }

    /**
     * 使用显式 S05 Permission Mode 装配 Headless Runtime。
     *
     * @param settings 已应用模型覆盖的 Provider 设置
     * @param workspace 已解析的真实 Workspace
     * @param timeout 每个 Run 的墙钟限制
     * @param permissionMode 当前 Headless Session 的权限模式
     */
    public RuntimeStdioCommandHandler(
            OpenAiCompatibleSettings settings,
            Path workspace,
            Duration timeout,
            PermissionMode permissionMode) {
        this(settings, workspace, timeout, permissionMode, SessionOpenRequest.create());
    }

    /**
     * 使用 CLI 已解析的持久 Session 选择装配 Headless Runtime。
     *
     * @param settings 已应用模型覆盖的 Provider 设置
     * @param workspace 已解析的真实 Workspace
     * @param timeout 每个 Run 的墙钟限制
     * @param permissionMode 当前 Permission Mode
     * @param sessionOpenRequest Create/Continue/Resume/Fork 选择
     */
    public RuntimeStdioCommandHandler(
            OpenAiCompatibleSettings settings,
            Path workspace,
            Duration timeout,
            PermissionMode permissionMode,
            SessionOpenRequest sessionOpenRequest) {
        approvals = new StdioApprovalCoordinator(this::emitApprovalRequest);
        application = new HeadlessRuntimeSession(
                Objects.requireNonNull(settings, "settings 不能为空"),
                this,
                new HeadlessRuntimeOptions(
                        workspace,
                        settings.model(),
                        timeout,
                        permissionMode,
                        java.util.List.of(),
                        Objects.requireNonNull(sessionOpenRequest, "sessionOpenRequest 不能为空"),
                        SessionStorage.defaultRoot()),
                approvals);
    }

    /**
     * 使用 Fake Model 装配真实 Runtime/stdio Adapter，供确定性契约测试使用。
     *
     * @param model 不访问网络的模型端口
     */
    RuntimeStdioCommandHandler(ModelGateway model) {
        this(
                model,
                new HeadlessRuntimeOptions(
                        Path.of("").toAbsolutePath().normalize(),
                        "fake-model",
                        io.github.liumaishenjian.ccjava.domain.AgentLimits.DEFAULT.maxDuration()));
    }

    /**
     * 使用 Fake Model 和显式 Workspace 装配真实 Runtime/stdio Adapter。
     *
     * @param model 不访问网络的模型端口
     * @param options 测试 Workspace 与墙钟配置
     */
    RuntimeStdioCommandHandler(
            ModelGateway model,
            HeadlessRuntimeOptions options) {
        approvals = new StdioApprovalCoordinator(this::emitApprovalRequest);
        application = new HeadlessRuntimeSession(
                Objects.requireNonNull(model, "model 不能为空"),
                this,
                Objects.requireNonNull(options, "options 不能为空"),
                approvals);
    }

    @Override
    public StdioProtocol.Disposition handle(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        return switch (command.type()) {
            case "initialize" -> initialize(command, events);
            case "run.start" -> startRun(command, events);
            case "run.cancel" -> cancelRun(command);
            case "approval.resolve" -> resolveApproval(command);
            case "checkpoint.list" -> listCheckpoints(command, events);
            case "checkpoint.diff" -> checkpointDiff(command, events);
            case "checkpoint.undo" -> checkpointUndo(command, events);
            case "shutdown" -> shutdown();
            default -> throw protocolError(
                    "UNKNOWN_COMMAND",
                    command,
                    "不支持该命令");
        };
    }

    private StdioProtocol.Disposition initialize(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        synchronized (lock) {
            ensureState(State.NEW, command);
            if (command.sessionId().isPresent() || command.runId().isPresent()) {
                throw protocolError(
                        "INVALID_STATE",
                        command,
                        "initialize 不能携带 Session 或 Run");
            }
            application.open();
            state = State.READY;
        }
        ObjectNode payload = codec.objectNode();
        payload.put("protocolVersion", StdioProtocol.VERSION);
        var sessionOpen = application.sessionOpenResult();
        payload.put("openMode", sessionOpen.mode().name().toLowerCase(Locale.ROOT));
        payload.put("readOnly", sessionOpen.readOnly());
        sessionOpen.parentSessionId().ifPresent(parent ->
                payload.put("parentSessionId", parent.value()));
        ArrayNode warnings = codec.arrayNode();
        sessionOpen.issues().forEach(issue ->
                warnings.add(issue.kind().name().toLowerCase(Locale.ROOT)));
        payload.set("warnings", warnings);
        events.emit(
                "initialized",
                command.requestId(),
                Optional.of(application.sessionId().value()),
                Optional.empty(),
                payload);
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition startRun(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        String prompt = requiredPrompt(command);
        ActiveRun run;
        synchronized (lock) {
            ensureState(State.READY, command);
            requireSession(command);
            if (command.runId().isPresent()) {
                throw protocolError(
                        "INVALID_STATE",
                        command,
                        "run.start 的 Run ID 必须由 Java 生成");
            }
            run = new ActiveRun(command.requestId(), prompt.length(), events);
            activeRun = run;
            state = State.RUNNING;
        }
        executor.submit(() -> executeRun(run, prompt));
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition listCheckpoints(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        synchronized (lock) {
            ensureState(State.READY, command);
            requireSession(command);
            requireNoRunId(command);
        }
        ArrayNode items = codec.arrayNode();
        for (var summary : application.checkpoints()) {
            ObjectNode item = codec.objectNode();
            item.put("checkpointId", summary.id().value());
            item.put("callId", summary.callId());
            item.put("toolName", summary.toolName());
            item.put("target", summary.target());
            item.put("existedBefore", summary.existedBefore());
            item.put("phase", summary.phase().name().toLowerCase(Locale.ROOT));
            item.put("undoable", summary.undoable());
            items.add(item);
        }
        ObjectNode payload = codec.objectNode();
        payload.set("checkpoints", items);
        events.emit(
                "checkpoint.listed",
                command.requestId(),
                Optional.of(application.sessionId().value()),
                Optional.empty(),
                payload);
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition checkpointDiff(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        String checkpointId;
        synchronized (lock) {
            ensureState(State.READY, command);
            requireSession(command);
            requireNoRunId(command);
            checkpointId = requiredCheckpointId(command);
        }
        var diff = application.checkpointDiff(
                new io.github.liumaishenjian.ccjava.domain.CheckpointId(checkpointId));
        ObjectNode payload = codec.objectNode();
        payload.put("checkpointId", diff.checkpointId().value());
        payload.put("target", diff.target());
        payload.put("status", diff.status().name().toLowerCase(Locale.ROOT));
        payload.put("text", diff.text());
        payload.put("truncated", diff.truncated());
        events.emit(
                "checkpoint.diffed",
                command.requestId(),
                Optional.of(application.sessionId().value()),
                Optional.empty(),
                payload);
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition checkpointUndo(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        String checkpointId;
        JsonNode confirmed = command.payload().get("confirmed");
        synchronized (lock) {
            ensureState(State.READY, command);
            requireSession(command);
            requireNoRunId(command);
            checkpointId = requiredCheckpointId(command);
            if (confirmed == null || !confirmed.isBoolean()) {
                throw protocolError("INVALID_PAYLOAD", command, "checkpoint.undo.confirmed 必须是布尔值");
            }
        }
        var result = application.undoCheckpoint(
                new io.github.liumaishenjian.ccjava.domain.CheckpointId(checkpointId),
                confirmed.booleanValue());
        ObjectNode payload = codec.objectNode();
        payload.put("checkpointId", result.checkpointId().value());
        payload.put("target", result.target());
        payload.put("status", result.status().name().toLowerCase(Locale.ROOT));
        payload.put("message", result.message());
        events.emit(
                "checkpoint.undone",
                command.requestId(),
                Optional.of(application.sessionId().value()),
                Optional.empty(),
                payload);
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition cancelRun(StdioProtocol.Command command)
            throws StdioProtocolException {
        synchronized (lock) {
            ensureState(State.RUNNING, command);
            requireSession(command);
            if (activeRun.runId == null
                    || command.runId().isEmpty()
                    || !activeRun.runId.value().equals(command.runId().orElseThrow())
                    || !application.cancel(activeRun.runId)) {
                throw protocolError(
                        "INVALID_STATE",
                        command,
                        "run.cancel 与活动 Run 不匹配或取消已经发生");
            }
        }
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition shutdown() {
        synchronized (lock) {
            state = State.CLOSED;
            if (activeRun != null && activeRun.runId != null) {
                application.cancel(activeRun.runId);
            }
        }
        approvals.close();
        return StdioProtocol.Disposition.SHUTDOWN;
    }

    private StdioProtocol.Disposition resolveApproval(StdioProtocol.Command command)
            throws StdioProtocolException {
        String approvalId;
        ApprovalResponse decision;
        synchronized (lock) {
            ensureState(State.RUNNING, command);
            requireSession(command);
            if (activeRun == null
                    || activeRun.runId == null
                    || command.runId().isEmpty()
                    || !activeRun.runId.value().equals(command.runId().orElseThrow())) {
                throw protocolError(
                        "INVALID_STATE",
                        command,
                        "approval.resolve 与活动 Run 不匹配");
            }
            JsonNode id = command.payload().get("approvalId");
            JsonNode rawDecision = command.payload().get("decision");
            if (id == null
                    || !id.isString()
                    || id.stringValue().isBlank()
                    || id.stringValue().length() > 128
                    || rawDecision == null
                    || !rawDecision.isString()) {
                throw protocolError(
                        "INVALID_PAYLOAD",
                        command,
                        "approval.resolve payload 无效");
            }
            approvalId = id.stringValue();
            decision = switch (rawDecision.stringValue()) {
                case "allow_once" -> ApprovalResponse.allowOnce();
                case "allow_session" -> {
                    StdioApprovalCoordinator.Request pending = approvals.pendingRequest();
                    if (pending == null || !pending.approvalId().equals(approvalId)) {
                        throw protocolError(
                                "STALE_APPROVAL",
                                command,
                                "审批不存在、已结束或与当前请求不匹配");
                    }
                    yield ApprovalResponse.allowSession(pending.scope());
                }
                case "deny" -> ApprovalResponse.deny();
                default -> throw protocolError(
                        "INVALID_PAYLOAD",
                        command,
                        "approval.resolve decision 无效");
            };
        }
        if (!approvals.resolve(approvalId, decision)) {
            throw protocolError(
                    "STALE_APPROVAL",
                    command,
                    "审批不存在、已结束或与当前请求不匹配");
        }
        return StdioProtocol.Disposition.CONTINUE;
    }

    private void emitApprovalRequest(StdioApprovalCoordinator.Request request) {
        ActiveRun run;
        synchronized (lock) {
            run = activeRun;
            if (run == null
                    || run.runId == null
                    || !run.runId.equals(request.runId())
                    || state != State.RUNNING) {
                throw new IllegalStateException("审批请求与活动 Run 不匹配");
            }
        }
        ObjectNode payload = codec.objectNode();
        payload.put("approvalId", request.approvalId());
        payload.put("ordinal", request.ordinal());
        payload.put("toolName", request.toolName());
        payload.put("effect", request.effect().name().toLowerCase(Locale.ROOT));
        payload.put("sessionScope", !request.scope().toolWide());
        if (!request.preview().target().isEmpty()) {
            payload.put("target", request.preview().target());
            payload.put("operation", request.preview().operation());
            payload.put("removedLines", request.preview().removedLines());
            payload.put("addedLines", request.preview().addedLines());
        }
        if (!request.preview().command().isEmpty()) {
            payload.put("command", request.preview().command());
            payload.put("shell", request.preview().shell());
            payload.put("workingDirectory", request.preview().workingDirectory());
            payload.put("operation", request.preview().operation());
        }
        emit(run, "approval.requested", payload);
    }

    private void executeRun(ActiveRun run, String prompt) {
        try {
            application.run(prompt);
        } catch (RuntimeException exception) {
            emitUnexpectedFailure(run);
        }
    }

    @Override
    public void publish(AgentEventEnvelope envelope) {
        ActiveRun run;
        synchronized (lock) {
            run = activeRun;
            if (run == null
                    || !envelope.sessionId().equals(application.sessionId())) {
                return;
            }
            if (envelope.event() instanceof LifecycleEvent.RunStarted) {
                run.runId = envelope.runId().orElseThrow();
            } else if (run.runId == null
                    || envelope.runId().isEmpty()
                    || !run.runId.equals(envelope.runId().orElseThrow())) {
                return;
            }
        }

        if (envelope.event() instanceof LifecycleEvent.RunStarted) {
            ObjectNode payload = codec.objectNode();
            payload.put("promptChars", run.promptChars);
            emit(run, "run.started", payload);
        } else if (envelope.event() instanceof LifecycleEvent.BeforeTool before) {
            ObjectNode payload = codec.objectNode();
            payload.put("ordinal", before.ordinal());
            payload.put("toolName", before.call().name());
            payload.put("status", "started");
            safeToolMode(before.call()).ifPresent(mode -> {
                run.toolModes.put(before.ordinal(), mode);
                payload.put("mode", mode);
            });
            emit(run, "tool.started", payload);
        } else if (envelope.event() instanceof LifecycleEvent.AfterTool after) {
            ObjectNode payload = codec.objectNode();
            payload.put("ordinal", after.ordinal());
            payload.put("toolName", after.result().toolName());
            payload.put("status", after.result().status().name().toLowerCase());
            payload.put("returnedCharacters", after.result().metadata().returnedCharacters());
            payload.put("returnedItems", after.result().metadata().returnedItems());
            payload.put("truncated", after.result().metadata().truncated());
            payload.put(
                    "truncationReason",
                    after.result().metadata().truncationReason().name().toLowerCase(Locale.ROOT));
            payload.put("filteredItems", after.result().metadata().filteredItems());
            Optional.ofNullable(run.toolModes.remove(after.ordinal()))
                    .ifPresent(mode -> payload.put("mode", mode));
            after.result().error().ifPresent(error -> payload.put(
                    "errorCode", error.code().name().toLowerCase()));
            String type = after.result().status()
                    == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS
                            ? "tool.completed" : "tool.failed";
            emit(run, type, payload);
        } else if (envelope.event() instanceof LifecycleEvent.ToolOutput output) {
            ObjectNode payload = codec.objectNode();
            payload.put("ordinal", output.ordinal());
            payload.put("toolName", output.toolName());
            payload.put("stream", output.stream().name().toLowerCase(Locale.ROOT));
            payload.put("text", output.text());
            emit(run, "tool.output", payload);
        } else if (envelope.event() instanceof ModelTextDelta delta) {
            ObjectNode payload = codec.objectNode();
            payload.put("text", delta.text());
            payload.put("turn", delta.turnNumber());
            emit(run, "model.text.delta", payload);
        } else if (envelope.event() instanceof LifecycleEvent.RunFinished finished) {
            emitTerminal(run, finished.result());
        }
    }

    private void emitTerminal(ActiveRun run, AgentRunResult result) {
        ObjectNode payload = codec.objectNode();
        payload.put("stopReason", result.stopReason().name().toLowerCase());
        payload.put("modelTurns", result.modelTurns());
        payload.put("toolCalls", result.toolCalls());
        result.finalText().ifPresent(value -> payload.put("finalText", value));
        result.modelFailure().ifPresent(value -> {
            ObjectNode failure = codec.objectNode();
            failure.put("category", value.category().name().toLowerCase(Locale.ROOT));
            value.statusClass().ifPresent(status -> failure.put(
                    "statusClass",
                    status == io.github.liumaishenjian.ccjava.domain.ModelHttpStatusClass.CLIENT_ERROR
                            ? "4xx"
                            : "5xx"));
            failure.put("attempts", value.attempts());
            failure.put("receivedOutput", value.receivedOutput());
            payload.set("modelFailure", failure);
        });
        application.telemetry(result.runId())
                .ifPresent(value -> payload.set("telemetry", telemetryPayload(value)));
        String type = switch (result.stopReason()) {
            case COMPLETED -> "run.completed";
            case USER_CANCELLED -> "run.cancelled";
            default -> "run.failed";
        };
        emit(run, type, payload);
        finish(run);
    }

    private ObjectNode telemetryPayload(RunTelemetry telemetry) {
        ObjectNode payload = codec.objectNode();
        payload.put("elapsedMillis", telemetry.elapsed().toMillis());
        payload.put("usageReportedTurns", telemetry.usageReportedTurns());
        payload.put("usageMissingTurns", telemetry.usageMissingTurns());

        ArrayNode modelTurns = codec.arrayNode();
        for (ModelTurnTelemetry turn : telemetry.modelTurns()) {
            ObjectNode item = codec.objectNode();
            item.put("turn", turn.turnNumber());
            item.put("elapsedMillis", turn.elapsed().toMillis());
            item.put("completed", turn.completed());
            turn.finishReason().ifPresent(
                    reason -> item.put("finishReason", reason.name().toLowerCase()));
            turn.usage().ifPresent(usage -> {
                ObjectNode usageNode = codec.objectNode();
                usageNode.put("inputTokens", usage.inputTokens());
                usageNode.put("outputTokens", usage.outputTokens());
                usageNode.put("totalTokens", usage.totalTokens());
                item.set("usage", usageNode);
            });
            modelTurns.add(item);
        }
        payload.set("modelTurns", modelTurns);

        ArrayNode toolCalls = codec.arrayNode();
        for (ToolCallTelemetry call : telemetry.toolCalls()) {
            ObjectNode item = codec.objectNode();
            item.put("ordinal", call.ordinal());
            item.put("elapsedMillis", call.elapsed().toMillis());
            item.put("completed", call.completed());
            toolCalls.add(item);
        }
        payload.set("toolCalls", toolCalls);

        telemetry.totalUsage().ifPresent(usage -> {
            ObjectNode usageNode = codec.objectNode();
            usageNode.put("inputTokens", usage.inputTokens());
            usageNode.put("outputTokens", usage.outputTokens());
            usageNode.put("totalTokens", usage.totalTokens());
            payload.set("totalUsage", usageNode);
        });
        return payload;
    }

    /**
     * 从 Tool Call 中只提取允许进入展示协议的固定枚举，不暴露查询、路径或其他参数。
     *
     * @param call 原始 Tool Call
     * @return search_text 的安全模式；非搜索或非法模式为空
     */
    static Optional<String> safeToolMode(ToolCall call) {
        Objects.requireNonNull(call, "call 不能为空");
        if (!"search_text".equals(call.name())) {
            return Optional.empty();
        }
        try {
            String mode = call.arguments().string("mode")
                    .orElse("content")
                    .toLowerCase(Locale.ROOT);
            return switch (mode) {
                case "content", "files", "count" -> Optional.of(mode);
                default -> Optional.empty();
            };
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private void emitUnexpectedFailure(ActiveRun run) {
        synchronized (lock) {
            if (activeRun != run || run.runId == null) {
                return;
            }
        }
        ObjectNode payload = codec.objectNode();
        payload.put("code", "RUNTIME_FAILURE");
        payload.put("stopReason", "internal_error");
        payload.put("modelTurns", 0);
        payload.put("toolCalls", 0);
        emit(run, "run.failed", payload);
        finish(run);
    }

    private void emit(ActiveRun run, String type, ObjectNode payload) {
        run.events.emit(
                type,
                run.requestId,
                Optional.of(application.sessionId().value()),
                Optional.of(run.runId.value()),
                payload);
    }

    private void finish(ActiveRun run) {
        synchronized (lock) {
            if (activeRun == run) {
                activeRun = null;
                if (state != State.CLOSED) {
                    state = State.READY;
                }
            }
        }
    }

    private String requiredPrompt(StdioProtocol.Command command)
            throws StdioProtocolException {
        JsonNode prompt = command.payload().get("prompt");
        if (prompt == null
                || !prompt.isString()
                || prompt.stringValue().isBlank()
                || prompt.stringValue().length() > HeadlessRuntimeSession.MAX_PROMPT_CHARS) {
            throw protocolError(
                    "INVALID_PAYLOAD",
                    command,
                    "run.start.prompt 为空或超过长度限制");
        }
        return prompt.stringValue();
    }

    private void ensureState(State expected, StdioProtocol.Command command)
            throws StdioProtocolException {
        if (state != expected) {
            throw protocolError(
                    "INVALID_STATE",
                    command,
                    "命令与当前 Application 状态不兼容");
        }
    }

    private void requireSession(StdioProtocol.Command command)
            throws StdioProtocolException {
        if (command.sessionId().isEmpty()
                || !application.sessionId().value().equals(command.sessionId().orElseThrow())) {
            throw protocolError(
                    "INVALID_STATE",
                    command,
                    "命令 Session 与当前连接不匹配");
        }
    }

    private void requireNoRunId(StdioProtocol.Command command) throws StdioProtocolException {
        if (command.runId().isPresent()) {
            throw protocolError("INVALID_STATE", command, "Checkpoint 命令不能携带 Run ID");
        }
    }

    private String requiredCheckpointId(StdioProtocol.Command command)
            throws StdioProtocolException {
        JsonNode value = command.payload().get("checkpointId");
        if (value == null
                || !value.isString()
                || value.stringValue().isBlank()
                || value.stringValue().length() > 128) {
            throw protocolError("INVALID_PAYLOAD", command, "checkpointId 为空或超过长度限制");
        }
        try {
            return new io.github.liumaishenjian.ccjava.domain.CheckpointId(
                            value.stringValue())
                    .value();
        } catch (IllegalArgumentException invalid) {
            throw protocolError("INVALID_PAYLOAD", command, "checkpointId 格式无效");
        }
    }

    private StdioProtocolException protocolError(
            String code,
            StdioProtocol.Command command,
            String message) {
        return new StdioProtocolException(code, command.requestId(), message);
    }

    @Override
    public void close() throws InterruptedException {
        synchronized (lock) {
            state = State.CLOSED;
            if (activeRun != null && activeRun.runId != null) {
                application.cancel(activeRun.runId);
            }
        }
        approvals.close();
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Runtime Run Executor 未退出");
            }
        }
        if (state != State.NEW) {
            application.close();
        }
    }

    private enum State {
        NEW,
        READY,
        RUNNING,
        CLOSED
    }

    private static final class ActiveRun {
        private final String requestId;
        private final int promptChars;
        private final StdioProtocol.EventEmitter events;
        private final Map<Integer, String> toolModes = new LinkedHashMap<>();
        private RunId runId;

        private ActiveRun(
                String requestId,
                int promptChars,
                StdioProtocol.EventEmitter events) {
            this.requestId = requestId;
            this.promptChars = promptChars;
            this.events = events;
        }
    }
}
