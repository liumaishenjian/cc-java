package io.github.liumaishenjian.ccjava.cli.stdio;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.ModelTurnTelemetry;
import io.github.liumaishenjian.ccjava.core.RunTelemetry;
import io.github.liumaishenjian.ccjava.core.ToolCallTelemetry;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.ModelTextDelta;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.model.springai.config.OpenAiCompatibleSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

import java.util.Objects;
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
        application = new HeadlessRuntimeSession(
                Objects.requireNonNull(settings, "settings 不能为空"),
                this,
                new HeadlessRuntimeOptions(workspace, settings.model(), timeout));
    }

    /**
     * 使用 Fake Model 装配真实 Runtime/stdio Adapter，供确定性契约测试使用。
     *
     * @param model 不访问网络的模型端口
     */
    RuntimeStdioCommandHandler(ModelGateway model) {
        application = new HeadlessRuntimeSession(
                Objects.requireNonNull(model, "model 不能为空"),
                this);
    }

    @Override
    public StdioProtocol.Disposition handle(
            StdioProtocol.Command command,
            StdioProtocol.EventEmitter events) throws StdioProtocolException {
        return switch (command.type()) {
            case "initialize" -> initialize(command, events);
            case "run.start" -> startRun(command, events);
            case "run.cancel" -> cancelRun(command);
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
        return StdioProtocol.Disposition.SHUTDOWN;
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
            emit(run, "tool.started", payload);
        } else if (envelope.event() instanceof LifecycleEvent.AfterTool after) {
            ObjectNode payload = codec.objectNode();
            payload.put("ordinal", after.ordinal());
            payload.put("toolName", after.result().toolName());
            payload.put("status", after.result().status().name().toLowerCase());
            payload.put("returnedCharacters", after.result().metadata().returnedCharacters());
            payload.put("truncated", after.result().metadata().truncated());
            payload.put("filteredItems", after.result().metadata().filteredItems());
            after.result().error().ifPresent(error -> payload.put(
                    "errorCode", error.code().name().toLowerCase()));
            String type = after.result().status()
                    == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS
                            ? "tool.completed" : "tool.failed";
            emit(run, type, payload);
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

    private void emitUnexpectedFailure(ActiveRun run) {
        synchronized (lock) {
            if (activeRun != run || run.runId == null) {
                return;
            }
        }
        ObjectNode payload = codec.objectNode();
        payload.put("code", "RUNTIME_FAILURE");
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
