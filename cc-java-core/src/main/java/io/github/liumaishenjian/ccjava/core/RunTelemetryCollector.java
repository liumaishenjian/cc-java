package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata;
import io.github.liumaishenjian.ccjava.domain.ModelUsage;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 从规范生命周期事件派生 Run、Model Turn 与 Tool Call 的安全观测快照。
 *
 * <p>采集器只保留事件时间、序号、完成状态、Finish Reason 和 Provider Usage。
 * 即使输入事件携带 Prompt、Completion、Tool 参数或结果，本类型也不会把这些内容保存到
 * {@link RunTelemetry}。采集异常不得影响 Agent Runtime，调用方仍应把本类型作为只读
 * {@link AgentEventSink} 使用。</p>
 *
 * <p>S02 使用事件的 UTC 时间戳计算墙钟耗时；若系统时钟回拨，负间隔会收敛为零。
 * 更高精度的单调时钟与外部 Metrics Backend 留到生产级 Harness 阶段。</p>
 *
 * @since 0.1.0
 */
public final class RunTelemetryCollector implements AgentEventSink {

    private final Map<RunId, MutableRun> active = new LinkedHashMap<>();
    private final Map<RunId, RunTelemetry> completed = new LinkedHashMap<>();

    /**
     * 创建不持有任何外部 Exporter 的进程内采集器。
     */
    public RunTelemetryCollector() {
    }

    /**
     * 消费一个规范事件；不相关、缺少 Run ID 或不属于活动 Run 的事件会被忽略。
     *
     * @param envelope 生命周期事件信封
     */
    @Override
    public synchronized void publish(AgentEventEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope 不能为空");
        Optional<RunId> runId = envelope.runId();
        if (runId.isEmpty()) {
            return;
        }
        RunId id = runId.orElseThrow();
        if (envelope.event() instanceof LifecycleEvent.RunStarted) {
            active.putIfAbsent(
                    id,
                    new MutableRun(envelope.sessionId(), id, envelope.occurredAt()));
            return;
        }
        MutableRun run = active.get(id);
        if (run == null) {
            return;
        }
        if (envelope.event() instanceof LifecycleEvent.ModelTurnStarted started) {
            run.startModelTurn(started.turnNumber(), envelope.occurredAt());
        } else if (envelope.event() instanceof LifecycleEvent.ModelTurnCompleted finished) {
            run.completeModelTurn(
                    finished.turnNumber(),
                    envelope.occurredAt(),
                    finished.turn().metadata());
        } else if (envelope.event() instanceof LifecycleEvent.BeforeTool before) {
            run.startTool(before.ordinal(), envelope.occurredAt());
        } else if (envelope.event() instanceof LifecycleEvent.AfterTool after) {
            run.completeTool(after.ordinal(), envelope.occurredAt());
        } else if (envelope.event() instanceof LifecycleEvent.RunFinished) {
            completed.put(id, run.freeze(envelope.occurredAt()));
            active.remove(id);
        }
    }

    /**
     * 返回已结束 Run 的观测快照。
     *
     * @param runId 目标 Run
     * @return Run 尚未结束或未被采集时为空
     */
    public synchronized Optional<RunTelemetry> find(RunId runId) {
        return Optional.ofNullable(completed.get(
                Objects.requireNonNull(runId, "runId 不能为空")));
    }

    private static Duration elapsed(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private static final class MutableRun {

        private final SessionId sessionId;
        private final RunId runId;
        private final Instant startedAt;
        private final Map<Integer, MutableModelTurn> modelTurns = new LinkedHashMap<>();
        private final Map<Integer, MutableToolCall> toolCalls = new LinkedHashMap<>();

        private MutableRun(SessionId sessionId, RunId runId, Instant startedAt) {
            this.sessionId = sessionId;
            this.runId = runId;
            this.startedAt = startedAt;
        }

        private void startModelTurn(int turnNumber, Instant occurredAt) {
            modelTurns.putIfAbsent(turnNumber, new MutableModelTurn(turnNumber, occurredAt));
        }

        private void completeModelTurn(
                int turnNumber,
                Instant occurredAt,
                ModelTurnMetadata metadata) {
            MutableModelTurn turn = modelTurns.get(turnNumber);
            if (turn != null) {
                turn.complete(occurredAt, metadata);
            }
        }

        private void startTool(int ordinal, Instant occurredAt) {
            toolCalls.putIfAbsent(ordinal, new MutableToolCall(ordinal, occurredAt));
        }

        private void completeTool(int ordinal, Instant occurredAt) {
            MutableToolCall call = toolCalls.get(ordinal);
            if (call != null) {
                call.complete(occurredAt);
            }
        }

        private RunTelemetry freeze(Instant endedAt) {
            List<ModelTurnTelemetry> turns = modelTurns.values().stream()
                    .map(turn -> turn.freeze(endedAt))
                    .toList();
            List<ToolCallTelemetry> tools = toolCalls.values().stream()
                    .map(call -> call.freeze(endedAt))
                    .toList();
            List<ModelUsage> reported = turns.stream()
                    .filter(ModelTurnTelemetry::completed)
                    .map(ModelTurnTelemetry::usage)
                    .flatMap(Optional::stream)
                    .toList();
            int completedTurns = (int) turns.stream()
                    .filter(ModelTurnTelemetry::completed)
                    .count();
            int missingTurns = completedTurns - reported.size();
            Optional<TokenUsageTotals> totals =
                    !reported.isEmpty() && missingTurns == 0
                            ? Optional.of(total(reported))
                            : Optional.empty();
            return new RunTelemetry(
                    sessionId,
                    runId,
                    elapsed(startedAt, endedAt),
                    turns,
                    tools,
                    reported.size(),
                    missingTurns,
                    totals);
        }

        private static TokenUsageTotals total(List<ModelUsage> usages) {
            long input = 0;
            long output = 0;
            long total = 0;
            for (ModelUsage usage : usages) {
                input = Math.addExact(input, usage.inputTokens());
                output = Math.addExact(output, usage.outputTokens());
                total = Math.addExact(total, usage.totalTokens());
            }
            return new TokenUsageTotals(input, output, total);
        }
    }

    private static final class MutableModelTurn {

        private final int turnNumber;
        private final Instant startedAt;
        private Instant completedAt;
        private ModelTurnMetadata metadata;

        private MutableModelTurn(int turnNumber, Instant startedAt) {
            this.turnNumber = turnNumber;
            this.startedAt = startedAt;
        }

        private void complete(Instant occurredAt, ModelTurnMetadata completedMetadata) {
            if (completedAt == null) {
                completedAt = occurredAt;
                metadata = completedMetadata;
            }
        }

        private ModelTurnTelemetry freeze(Instant runEndedAt) {
            boolean completed = completedAt != null;
            return new ModelTurnTelemetry(
                    turnNumber,
                    elapsed(startedAt, completed ? completedAt : runEndedAt),
                    completed,
                    completed ? Optional.of(metadata.finishReason()) : Optional.empty(),
                    completed ? metadata.usage() : Optional.empty());
        }
    }

    private static final class MutableToolCall {

        private final int ordinal;
        private final Instant startedAt;
        private Instant completedAt;

        private MutableToolCall(int ordinal, Instant startedAt) {
            this.ordinal = ordinal;
            this.startedAt = startedAt;
        }

        private void complete(Instant occurredAt) {
            if (completedAt == null) {
                completedAt = occurredAt;
            }
        }

        private ToolCallTelemetry freeze(Instant runEndedAt) {
            return new ToolCallTelemetry(
                    ordinal,
                    elapsed(startedAt, completedAt == null ? runEndedAt : completedAt),
                    completedAt != null);
        }
    }
}
