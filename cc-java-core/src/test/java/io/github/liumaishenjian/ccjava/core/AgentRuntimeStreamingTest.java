package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelTextDelta;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelUsage;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.RunStatus;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentRuntimeStreamingTest {

    private static final Instant START = Instant.parse("2026-07-28T01:00:00Z");

    @Test
    void publishesTextDeltasInOrderBeforeAggregatedTurnCompletes() {
        ScriptedModelGateway model = ScriptedModelGateway.scripted(
                ScriptedModelGateway.returning(ModelTurn.text("任务完成"), "任", "务", "完成"));
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run("流式回答", AgentLimits.DEFAULT);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.finalText()).contains("任务完成");
        assertThat(harness.events().envelopes().stream()
                .map(envelope -> envelope.event())
                .filter(ModelTextDelta.class::isInstance)
                .map(ModelTextDelta.class::cast)
                .map(ModelTextDelta::text)
                .toList())
                .containsExactly("任", "务", "完成");
        int lastDelta = lastIndexOfEvent(harness, ModelTextDelta.class);
        int turnCompleted = lastIndexOfEvent(harness, LifecycleEvent.ModelTurnCompleted.class);
        assertThat(turnCompleted).isGreaterThan(lastDelta);
    }

    @Test
    void retriesRetryableFailureOnlyBeforeFirstVisibleDelta() {
        AtomicInteger firstAttempt = new AtomicInteger();
        AtomicInteger secondAttempt = new AtomicInteger();
        ModelGatewayException rateLimit = new ModelGatewayException(
                ModelFailureKind.RATE_LIMITED,
                "Provider 暂时限流",
                true,
                false);
        ScriptedModelGateway model = ScriptedModelGateway.scripted(
                context -> {
                    firstAttempt.set(context.attemptNumber());
                    throw rateLimit;
                },
                context -> {
                    secondAttempt.set(context.attemptNumber());
                    context.observer().onTextDelta("恢复");
                    return ModelTurn.text("恢复完成");
                });
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run(
                "重试模型",
                new AgentLimits(2, 0, Duration.ofMinutes(1), 1));

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(result.modelTurns()).isEqualTo(1);
        assertThat(model.requests()).hasSize(2);
        assertThat(model.requests())
                .extracting(request -> request.turnNumber())
                .containsExactly(1, 1);
        assertThat(firstAttempt).hasValue(1);
        assertThat(secondAttempt).hasValue(2);
    }

    @Test
    void stopsAfterConfiguredModelRetryBudgetIsExhausted() {
        ModelGatewayException transientFailure = new ModelGatewayException(
                ModelFailureKind.TEMPORARILY_UNAVAILABLE,
                "Provider 暂时不可用",
                true,
                false);
        ScriptedModelGateway model = ScriptedModelGateway.scripted(
                ScriptedModelGateway.failing(transientFailure),
                ScriptedModelGateway.failing(transientFailure),
                ScriptedModelGateway.returning(ModelTurn.text("不应无限重试")));
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run(
                "有界重试",
                new AgentLimits(2, 0, Duration.ofMinutes(1), 1));

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_ERROR);
        assertThat(result.modelTurns()).isEqualTo(1);
        assertThat(model.requests()).hasSize(2);
        assertThat(model.remainingTurns()).isEqualTo(1);
    }

    @Test
    void doesNotRetryAfterVisibleDeltaEvenWhenFailureIsMarkedRetryable() {
        ModelGatewayException transientFailure = new ModelGatewayException(
                ModelFailureKind.TEMPORARILY_UNAVAILABLE,
                "流在部分输出后中断",
                true,
                false);
        ScriptedModelGateway model = ScriptedModelGateway.scripted(
                ScriptedModelGateway.failing(transientFailure, "部分"),
                ScriptedModelGateway.returning(ModelTurn.text("不应重试")));
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run(
                "部分流失败",
                new AgentLimits(2, 0, Duration.ofMinutes(1), 1));

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_ERROR);
        assertThat(model.requests()).hasSize(1);
        assertThat(model.remainingTurns()).isEqualTo(1);
        assertThat(textDeltas(harness)).containsExactly("部分");
    }

    @Test
    void doesNotRetryAnUnpublishedPartialToolResponse() {
        ModelGatewayException partialToolFailure = new ModelGatewayException(
                ModelFailureKind.INCOMPLETE_RESPONSE,
                "Tool Call 参数流不完整",
                true,
                true);
        ScriptedModelGateway model = ScriptedModelGateway.scripted(
                ScriptedModelGateway.failing(partialToolFailure),
                ScriptedModelGateway.returning(ModelTurn.text("不应重试")));
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run(
                "Tool Chunk 失败",
                new AgentLimits(2, 0, Duration.ofMinutes(1), 1));

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_ERROR);
        assertThat(model.requests()).hasSize(1);
        assertThat(model.remainingTurns()).isEqualTo(1);
    }

    @Test
    void mapsLocalResponseLimitToOutputLimitWithoutRetryingPartialOutput() {
        ModelGatewayException responseLimit = new ModelGatewayException(
                ModelFailureKind.RESPONSE_LIMIT_EXCEEDED,
                "模型响应超过本地安全上限",
                false,
                true);
        ScriptedModelGateway model = ScriptedModelGateway.scripted(
                ScriptedModelGateway.failing(responseLimit, "已发布"),
                ScriptedModelGateway.returning(ModelTurn.text("不应重试")));
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run(
                "本地响应上限",
                new AgentLimits(2, 0, Duration.ofMinutes(1), 1));

        assertThat(result.stopReason())
                .isEqualTo(StopReason.MODEL_OUTPUT_LIMIT_REACHED);
        assertThat(model.requests()).hasSize(1);
        assertThat(model.remainingTurns()).isEqualTo(1);
        assertThat(textDeltas(harness)).containsExactly("已发布");
    }

    @Test
    void cancellationSuppressesLateDeltaAndKeepsSessionReusable() throws Exception {
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch cancellationObserved = new CountDownLatch(1);
        ScriptedModelGateway model = ScriptedModelGateway.scripted(
                context -> {
                    modelStarted.countDown();
                    try (CancellationToken.Registration ignored =
                            context.cancellationToken().onCancellation(
                                    cancellationObserved::countDown)) {
                        if (!cancellationObserved.await(5, TimeUnit.SECONDS)) {
                            throw new ModelGatewayException("测试未观察到取消");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw ModelGatewayException.cancelled("等待取消时被中断");
                    }
                    context.observer().onTextDelta("迟到文本");
                    return ModelTurn.text("不应完成");
                },
                ScriptedModelGateway.returning(ModelTurn.text("Session 仍可使用")));
        Harness harness = newHarness(model);
        CancellationSource cancellation = new CancellationSource();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<AgentRunResult> future = executor.submit(() -> harness.run(
                    "取消当前流",
                    AgentLimits.DEFAULT,
                    cancellation.token()));
            assertThat(modelStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(cancellation.cancel()).isTrue();
            AgentRunResult cancelled = future.get(5, TimeUnit.SECONDS);

            assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(cancelled.stopReason()).isEqualTo(StopReason.USER_CANCELLED);
            assertThat(textDeltas(harness)).isEmpty();
            assertThat(harness.events().envelopes().stream()
                    .filter(envelope -> envelope.event() instanceof LifecycleEvent.RunFinished))
                    .hasSize(1);
        }

        AgentRunResult next = harness.run("继续会话", AgentLimits.DEFAULT);
        assertThat(next.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(next.finalText()).contains("Session 仍可使用");
    }

    @Test
    void ignoresDeltaPublishedAfterProviderAttemptHasReturned() throws Exception {
        AtomicReference<ModelTurnObserver> observer = new AtomicReference<>();
        ScriptedModelGateway model = ScriptedModelGateway.scripted(
                context -> {
                    observer.set(context.observer());
                    return ModelTurn.text("正常完成");
                });
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run("保存旧 Observer", AgentLimits.DEFAULT);
        observer.get().onTextDelta("回合完成后的迟到文本");

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(textDeltas(harness)).isEmpty();
        assertThat(harness.events().envelopes().get(
                harness.events().envelopes().size() - 1).event())
                .isInstanceOf(LifecycleEvent.RunFinished.class);
    }

    @Test
    void aggregatesUsageOnlyWhenEveryLogicalTurnReportsIt() {
        ToolCall call = new ToolCall("usage-call", "echo", JsonObject.empty());
        ModelTurn toolTurn = new ModelTurn(
                AssistantMessage.tools(List.of(call)),
                ModelFinishReason.TOOL_CALLS,
                Optional.of(new ModelUsage(10, 2, 12)));
        ModelTurn finalTurn = new ModelTurn(
                AssistantMessage.text("完成"),
                ModelFinishReason.STOP,
                Optional.of(new ModelUsage(5, 3, 8)));
        ScriptedModelGateway model = ScriptedModelGateway.of(toolTurn, finalTurn);
        Harness harness = newHarness(
                model,
                RecordingAgentTool.succeeding("echo", "ok"));

        AgentRunResult complete = harness.run("统计 Usage", AgentLimits.DEFAULT);

        assertThat(complete.usage()).contains(new ModelUsage(15, 5, 20));
        assertThat(complete.modelTurns()).isEqualTo(2);

        ModelTurn reportedFirst = new ModelTurn(
                AssistantMessage.tools(List.of(new ToolCall(
                        "missing-usage-call",
                        "echo",
                        JsonObject.empty()))),
                ModelFinishReason.TOOL_CALLS,
                Optional.of(new ModelUsage(2, 1, 3)));
        ScriptedModelGateway incompleteModel = ScriptedModelGateway.of(
                reportedFirst,
                ModelTurn.text("第二回合没有 Usage"));
        Harness incompleteHarness = newHarness(
                incompleteModel,
                RecordingAgentTool.succeeding("echo", "ok"));

        AgentRunResult incomplete = incompleteHarness.run(
                "Usage 不完整",
                AgentLimits.DEFAULT);

        assertThat(incomplete.usage()).isEmpty();
    }

    @Test
    void stopsExplicitlyWhenProviderReportsLengthFinishReason() {
        ModelTurn lengthLimited = new ModelTurn(
                AssistantMessage.text("部分答案"),
                ModelFinishReason.LENGTH,
                Optional.of(new ModelUsage(7, 4, 11)));
        ScriptedModelGateway model = ScriptedModelGateway.scripted(
                ScriptedModelGateway.returning(lengthLimited, "部分", "答案"));
        Harness harness = newHarness(model);

        AgentRunResult result = harness.run("触发输出上限", AgentLimits.DEFAULT);

        assertThat(result.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(result.stopReason())
                .isEqualTo(StopReason.MODEL_OUTPUT_LIMIT_REACHED);
        assertThat(result.finalText()).isEmpty();
        assertThat(result.usage()).contains(new ModelUsage(7, 4, 11));
        assertThat(harness.session().messages())
                .containsExactly(new UserMessage("触发输出上限"));
        assertThat(model.requests()).hasSize(1);
    }

    @Test
    void propagatesDeadlineAndMapsExceededDeadlineToStableStopReason() {
        MutableClock clock = new MutableClock(START);
        AtomicReference<Instant> observedDeadline = new AtomicReference<>();
        ScriptedModelGateway model = ScriptedModelGateway.scripted(context -> {
            observedDeadline.set(context.deadline().orElseThrow());
            clock.advance(Duration.ofSeconds(2));
            return ModelTurn.text("截止时间后返回");
        });
        Harness harness = newHarness(model, clock);
        AgentLimits limits = new AgentLimits(
                2,
                0,
                Duration.ofSeconds(1),
                0);

        AgentRunResult result = harness.run("Deadline", limits);

        assertThat(observedDeadline).hasValue(START.plusSeconds(1));
        assertThat(result.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(result.stopReason()).isEqualTo(StopReason.TIME_LIMIT_REACHED);
        assertThat(result.finalText()).isEmpty();
    }

    @Test
    void derivesModelTurnDurationFromDeterministicLifecycleBoundaries() {
        MutableClock clock = new MutableClock(START);
        ScriptedModelGateway model = ScriptedModelGateway.scripted(context -> {
            clock.advance(Duration.ofMillis(275));
            return ModelTurn.text("完成");
        });
        Harness harness = newHarness(model, clock);

        AgentRunResult result = harness.run("度量模型回合", AgentLimits.DEFAULT);

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        Instant turnStarted = harness.events().envelopes().stream()
                .filter(envelope ->
                        envelope.event() instanceof LifecycleEvent.ModelTurnStarted)
                .findFirst()
                .orElseThrow()
                .occurredAt();
        Instant turnCompleted = harness.events().envelopes().stream()
                .filter(envelope ->
                        envelope.event() instanceof LifecycleEvent.ModelTurnCompleted)
                .findFirst()
                .orElseThrow()
                .occurredAt();
        assertThat(Duration.between(turnStarted, turnCompleted))
                .isEqualTo(Duration.ofMillis(275));
    }

    @Test
    void alwaysClearsActiveRunWhenUnexpectedErrorEscapes() {
        ScriptedModelGateway model = ScriptedModelGateway.scripted(
                ignored -> {
                    throw new AssertionError("模拟测试断言错误");
                },
                ScriptedModelGateway.returning(ModelTurn.text("第二次 Run 成功")));
        Harness harness = newHarness(model);

        assertThatThrownBy(() -> harness.run("首个 Run", AgentLimits.DEFAULT))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("模拟测试断言错误");

        AgentRunResult second = harness.run("第二个 Run", AgentLimits.DEFAULT);
        assertThat(second.stopReason()).isEqualTo(StopReason.COMPLETED);
    }

    private static List<String> textDeltas(Harness harness) {
        return harness.events().envelopes().stream()
                .map(envelope -> envelope.event())
                .filter(ModelTextDelta.class::isInstance)
                .map(ModelTextDelta.class::cast)
                .map(ModelTextDelta::text)
                .toList();
    }

    private static int lastIndexOfEvent(Harness harness, Class<?> eventType) {
        List<?> events = harness.events().envelopes().stream()
                .map(envelope -> envelope.event())
                .toList();
        for (int index = events.size() - 1; index >= 0; index--) {
            if (eventType.isInstance(events.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static Harness newHarness(
            ModelGateway model,
            AgentTool... tools) {
        return newHarness(model, Clock.fixed(START, ZoneOffset.UTC), tools);
    }

    private static Harness newHarness(ModelGateway model, Clock clock, AgentTool... tools) {
        RecordingAgentEventSink eventSink = new RecordingAgentEventSink();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(clock, eventSink);
        SequentialAgentIdGenerator idGenerator = new SequentialAgentIdGenerator();
        InMemorySessionStore sessionStore = new InMemorySessionStore(
                idGenerator,
                lifecycle);
        ToolRegistry registry = new ToolRegistry(Arrays.asList(tools));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                registry,
                (ignoredInvocation, ignoredDefinition) -> PermissionDecision.ALLOW,
                (ignoredInvocation, ignoredDefinition) -> PermissionDecision.ALLOW,
                lifecycle);
        AgentRuntime runtime = new AgentRuntime(
                sessionStore,
                idGenerator,
                model,
                new DefaultContextAssembler(),
                registry,
                pipeline,
                lifecycle,
                clock);
        AgentSession session = sessionStore.create(SessionSpec.of(
                "你是一个只执行 S02 离线协议测试的 Agent。"));
        return new Harness(runtime, session, eventSink);
    }

    private record Harness(
            AgentRuntime runtime,
            AgentSession session,
            RecordingAgentEventSink events) {

        AgentRunResult run(String message, AgentLimits limits) {
            return run(message, limits, CancellationToken.none());
        }

        AgentRunResult run(
                String message,
                AgentLimits limits,
                CancellationToken cancellationToken) {
            return runtime.run(
                    session.id(),
                    new AgentRunRequest(new UserMessage(message), limits),
                    cancellationToken);
        }
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initialInstant) {
            instant = new AtomicReference<>(initialInstant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("测试 Clock 只支持 UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }

        private void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }
    }
}
