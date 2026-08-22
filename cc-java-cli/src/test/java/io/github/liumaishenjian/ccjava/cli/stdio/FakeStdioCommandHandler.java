package io.github.liumaishenjian.ccjava.cli.stdio;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 只用于 Spike 测试的确定性 Application Handler。
 *
 * <p>它模拟异步模型 Delta 与取消，不访问网络、不执行 Tool，也不冒充真实
 * {@code AgentRuntime}。未来真实装配必须替换本类，而不是把 Fake 带入生产入口。</p>
 */
final class FakeStdioCommandHandler implements StdioProtocol.CommandHandler {

    private static final int MAX_PROMPT_CHARS = 8 * 1024;

    private final Object lock = new Object();
    private final StdioProtocolCodec codec = new StdioProtocolCodec();
    private final List<String> deltas;
    private final Duration deltaDelay;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("cc-java-fake-run").daemon(true).factory());
    private State state = State.NEW;
    private int nextRunNumber = 1;
    private String sessionId;
    private ActiveRun activeRun;

    FakeStdioCommandHandler(List<String> deltas, Duration deltaDelay) {
        this.deltas = List.copyOf(Objects.requireNonNull(deltas, "deltas 不能为空"));
        this.deltaDelay = Objects.requireNonNull(deltaDelay, "deltaDelay 不能为空");
        if (deltaDelay.isNegative()) {
            throw new IllegalArgumentException("deltaDelay 不能为负数");
        }
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
                    "Fake Handler 不支持该命令");
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
            sessionId = "session-1";
            state = State.READY;
        }
        ObjectNode payload = codec.objectNode();
        payload.put("protocolVersion", StdioProtocol.VERSION);
        events.emit(
                "initialized",
                command.requestId(),
                Optional.of(sessionId),
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
            run = new ActiveRun(
                    "run-" + nextRunNumber++,
                    command.requestId(),
                    new AtomicBoolean(),
                    new AtomicBoolean());
            activeRun = run;
            state = State.RUNNING;
        }

        ObjectNode accepted = codec.objectNode();
        accepted.put("commandType", "run.start");
        accepted.put("disposition", "accepted");
        accepted.put("code", "ACCEPTED");
        events.emit(
                "run.command.result",
                command.requestId(),
                Optional.of(sessionId),
                Optional.empty(),
                accepted);

        ObjectNode started = codec.objectNode();
        started.put("promptChars", prompt.length());
        events.emit(
                "run.started",
                command.requestId(),
                Optional.of(sessionId),
                Optional.of(run.runId()),
                started);
        executor.submit(() -> produceRun(run, events));
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition cancelRun(StdioProtocol.Command command)
            throws StdioProtocolException {
        ActiveRun run;
        synchronized (lock) {
            ensureState(State.RUNNING, command);
            requireSession(command);
            run = activeRun;
            if (command.runId().isEmpty()
                    || !run.runId().equals(command.runId().orElseThrow())) {
                throw protocolError(
                        "INVALID_STATE",
                        command,
                        "run.cancel 与活动 Run 不匹配");
            }
            run.cancelled().set(true);
        }
        return StdioProtocol.Disposition.CONTINUE;
    }

    private StdioProtocol.Disposition shutdown() {
        synchronized (lock) {
            state = State.CLOSED;
            if (activeRun != null) {
                activeRun.cancelled().set(true);
            }
        }
        return StdioProtocol.Disposition.SHUTDOWN;
    }

    private void produceRun(
            ActiveRun run,
            StdioProtocol.EventEmitter events) {
        try {
            for (String delta : deltas) {
                if (run.cancelled().get()) {
                    emitCancelled(run, events);
                    return;
                }
                if (!deltaDelay.isZero()) {
                    Thread.sleep(deltaDelay);
                }
                if (run.cancelled().get()) {
                    emitCancelled(run, events);
                    return;
                }
                ObjectNode payload = codec.objectNode();
                payload.put("text", delta);
                events.emit(
                        "model.text.delta",
                        run.requestId(),
                        Optional.of(sessionId),
                        Optional.of(run.runId()),
                        payload);
            }
            emitCompleted(run, events);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            emitCancelled(run, events);
        } catch (RuntimeException exception) {
            emitFailed(run, events);
        }
    }

    private void emitCompleted(
            ActiveRun run,
            StdioProtocol.EventEmitter events) {
        if (!run.terminal().compareAndSet(false, true)) {
            return;
        }
        ObjectNode payload = codec.objectNode();
        payload.put("stopReason", "completed");
        events.emit(
                "run.completed",
                run.requestId(),
                Optional.of(sessionId),
                Optional.of(run.runId()),
                payload);
        finish(run);
    }

    private void emitCancelled(
            ActiveRun run,
            StdioProtocol.EventEmitter events) {
        if (!run.terminal().compareAndSet(false, true)) {
            return;
        }
        ObjectNode payload = codec.objectNode();
        payload.put("stopReason", "cancelled");
        events.emit(
                "run.cancelled",
                run.requestId(),
                Optional.of(sessionId),
                Optional.of(run.runId()),
                payload);
        finish(run);
    }

    private void emitFailed(
            ActiveRun run,
            StdioProtocol.EventEmitter events) {
        if (!run.terminal().compareAndSet(false, true)) {
            return;
        }
        ObjectNode payload = codec.objectNode();
        payload.put("code", "FAKE_RUN_FAILURE");
        events.emit(
                "run.failed",
                run.requestId(),
                Optional.of(sessionId),
                Optional.of(run.runId()),
                payload);
        finish(run);
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
                || prompt.stringValue().length() > MAX_PROMPT_CHARS) {
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
                || !sessionId.equals(command.sessionId().orElseThrow())) {
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
            if (activeRun != null) {
                activeRun.cancelled().set(true);
            }
        }
        executor.shutdown();
        if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Fake Run Executor 未退出");
            }
        }
    }

    private enum State {
        NEW,
        READY,
        RUNNING,
        CLOSED
    }

    private record ActiveRun(
            String runId,
            String requestId,
            AtomicBoolean cancelled,
            AtomicBoolean terminal) {
    }
}
