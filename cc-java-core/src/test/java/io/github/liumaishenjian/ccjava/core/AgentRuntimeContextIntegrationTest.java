package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.RunStatus;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.SummaryCandidate;
import io.github.liumaishenjian.ccjava.domain.SummaryRequest;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 证伪 S07 Projection 只进入模型请求边界且 Run 终态清理全部临时状态。 */
class AgentRuntimeContextIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC);
    private static final String SYSTEM = "system";

    @Test
    void appliesC1C2ToCompleteToolBatchWithoutMutatingCanonicalTranscript() {
        SequentialAgentIdGenerator ids = new SequentialAgentIdGenerator();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, AgentEventSink.noop());
        InMemorySessionStore sessions = new InMemorySessionStore(ids, lifecycle);
        RecordingAgentTool tool = RecordingAgentTool.succeeding("echo", "x".repeat(300));
        ToolRegistry tools = new ToolRegistry(List.of(tool));
        ToolExecutionPipeline pipeline = pipeline(tools, lifecycle);
        AgentSession session = sessions.create(SessionSpec.of(SYSTEM));
        ScriptedModelGateway seedModel = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(call("old-call"))), ModelTurn.text("seed done"));
        new AgentRuntime(
                sessions, ids, seedModel, new DefaultContextAssembler(), tools, pipeline, lifecycle)
                .run(session.id(), request("seed"));
        List<AgentMessage> canonicalBefore = session.messages();

        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelTurn.tools(List.of(call("new-call"))),
                ModelTurn.text("projected"));
        RecordingJournal journal = new RecordingJournal();
        ContextPreparationService preparation = configured((summary, cancellation) -> Optional.empty());
        AgentRuntime runtime = new AgentRuntime(
                sessions,
                ids,
                model,
                new DefaultContextAssembler(),
                tools,
                pipeline,
                lifecycle,
                journal,
                preparation);

        AgentRunResult result = runtime.run(session.id(), request("next"));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(model.requests()).hasSize(2);
        assertThat(preparation.activeRunCount()).isZero();
        assertThat(session.messages().subList(0, canonicalBefore.size()))
                .containsExactlyElementsOf(canonicalBefore);
        assertThat(canonicalBefore).filteredOn(ToolResultMessage.class::isInstance)
                .singleElement()
                .satisfies(message -> assertThat(((ToolResultMessage) message).result().content())
                        .isEqualTo("x".repeat(300)));
        assertThat(journal.messages())
                .containsExactly(
                        new UserMessage("next"),
                        AssistantMessage.tools(List.of(call("new-call"))),
                        AssistantMessage.text("projected"))
                .noneMatch(io.github.liumaishenjian.ccjava.domain.ContextSummaryMessage.class::isInstance);
        for (ModelRequest projectedRequest : model.requests()) {
            assertThat(projectedRequest.toolDefinitions())
                    .containsExactlyElementsOf(tools.definitions());
            assertCompleteToolBatches(projectedRequest.messages());
            assertThat(projectedRequest.messages()).filteredOn(ToolResultMessage.class::isInstance)
                    .first()
                    .satisfies(message -> assertThat(((ToolResultMessage) message).result().content())
                            .contains("C1"));
        }
    }

    @Test
    void C3ThenC4AreConditionalAndOnlyProjectionReachesGateway() {
        Fixture fixture = fixtureWithHistory();
        List<SummaryRequest> summaryRequests = new ArrayList<>();
        ContextSummarizer summarizer = (request, cancellation) -> {
            summaryRequests.add(request);
            if (request.tier() == io.github.liumaishenjian.ccjava.domain.SummaryTier.C3_ROLLING) {
                return Optional.of(candidate(request, "r"));
            }
            return Optional.of(candidate(request, "compact"));
        };
        ContextPreparationService preparation = configured(summarizer);
        ScriptedModelGateway model = ScriptedModelGateway.of(ModelTurn.text("done"));
        AgentRuntime runtime = fixture.runtime(model, preparation);
        List<AgentMessage> canonicalBefore = fixture.session().messages();

        AgentRunResult result = runtime.run(
                fixture.session().id(), request("active " + "a".repeat(120)));

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(summaryRequests)
                .extracting(SummaryRequest::tier)
                .containsExactly(
                        io.github.liumaishenjian.ccjava.domain.SummaryTier.C3_ROLLING,
                        io.github.liumaishenjian.ccjava.domain.SummaryTier.C4_FULL);
        assertThat(model.requests().getFirst().messages())
                .anyMatch(io.github.liumaishenjian.ccjava.domain.ContextSummaryMessage.class::isInstance);
        assertThat(fixture.session().messages().subList(0, canonicalBefore.size()))
                .containsExactlyElementsOf(canonicalBefore);
        assertThat(fixture.session().messages())
                .noneMatch(io.github.liumaishenjian.ccjava.domain.ContextSummaryMessage.class::isInstance);
        assertThat(preparation.activeRunCount()).isZero();
    }

    @Test
    void retriesOnceWithForcedSummaryAfterTypedContextOverflow() {
        Fixture fixture = fixtureWithHistory();
        AtomicInteger calls = new AtomicInteger();
        List<ModelRequest> requests = new ArrayList<>();
        ContextPreparationService preparation = configured((request, cancellation) ->
                Optional.of(candidate(request, "overflow compact")));
        ModelGateway model = request -> {
            requests.add(request);
            if (calls.getAndIncrement() == 0) {
                throw new ModelGatewayException(
                        ModelGatewayException.FailureKind.CONTEXT_OVERFLOW,
                        "context overflow");
            }
            return ModelTurn.text("recovered");
        };

        AgentRunResult result = fixture.runtime(model, preparation)
                .run(fixture.session().id(), request("active"));

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(calls).hasValue(2);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).messages())
                .anyMatch(io.github.liumaishenjian.ccjava.domain.ContextSummaryMessage.class::isInstance);
        assertThat(requests.get(1).toolDefinitions())
                .containsExactlyElementsOf(requests.get(0).toolDefinitions());
        assertCompleteToolBatches(requests.get(1).messages());
        assertThat(preparation.activeRunCount()).isZero();
    }

    @Test
    void secondTypedContextOverflowStopsAfterExactlyTwoGatewayAttempts() {
        Fixture fixture = fixtureWithHistory();
        AtomicInteger calls = new AtomicInteger();
        ContextPreparationService preparation = configured((request, cancellation) ->
                Optional.of(candidate(request, "overflow compact")));
        ModelGateway model = request -> {
            calls.incrementAndGet();
            throw new ModelGatewayException(
                    ModelGatewayException.FailureKind.CONTEXT_OVERFLOW,
                    "context overflow");
        };

        AgentRunResult result = fixture.runtime(model, preparation)
                .run(fixture.session().id(), request("active"));

        assertThat(result.stopReason()).isEqualTo(StopReason.CONTEXT_LIMIT_REACHED);
        assertThat(calls).hasValue(2);
        assertThat(preparation.activeRunCount()).isZero();
    }

    @Test
    void cancellationAfterTypedContextOverflowPreventsSummaryAndRetry() throws Exception {
        Fixture fixture = fixtureWithHistory();
        CountDownLatch firstOverflow = new CountDownLatch(1);
        CountDownLatch releaseFailure = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger summaryCalls = new AtomicInteger();
        ContextPreparationService preparation = configured((request, cancellation) -> {
            summaryCalls.incrementAndGet();
            return Optional.of(candidate(request, "unexpected"));
        });
        ModelGateway model = request -> {
            modelCalls.incrementAndGet();
            firstOverflow.countDown();
            await(releaseFailure);
            throw new ModelGatewayException(
                    ModelGatewayException.FailureKind.CONTEXT_OVERFLOW,
                    "context overflow");
        };
        AgentRuntime runtime = fixture.runtime(model, preparation);
        AtomicReference<AgentRunResult> result = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> result.set(
                runtime.run(fixture.session().id(), request("active"))));
        assertThat(firstOverflow.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.cancel(
                fixture.session().id(),
                new io.github.liumaishenjian.ccjava.domain.RunId("run-2")))
                .isTrue();
        releaseFailure.countDown();
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(result.get().stopReason()).isEqualTo(StopReason.USER_CANCELLED);
        assertThat(modelCalls).hasValue(1);
        assertThat(summaryCalls).hasValue(0);
        assertThat(preparation.activeRunCount()).isZero();
    }

    @Test
    void nonOverflowFailureNeverSummarizesOrRetries() {
        Fixture fixture = fixtureWithHistory();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger summaryCalls = new AtomicInteger();
        ContextPreparationService preparation = configured((request, cancellation) -> {
            summaryCalls.incrementAndGet();
            return Optional.of(candidate(request, "unexpected"));
        });
        ModelGateway model = request -> {
            modelCalls.incrementAndGet();
            throw new ModelGatewayException(
                    ModelGatewayException.FailureKind.PERMANENT,
                    "permanent");
        };

        AgentRunResult result = fixture.runtime(model, preparation)
                .run(fixture.session().id(), request("active"));

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_ERROR);
        assertThat(modelCalls).hasValue(1);
        assertThat(summaryCalls).hasValue(0);
        assertThat(preparation.activeRunCount()).isZero();
    }

    @Test
    void streamingDeltaBeforeTypedOverflowPreventsRetry() {
        Fixture fixture = fixtureWithHistory();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger summaryCalls = new AtomicInteger();
        ContextPreparationService preparation = configured((request, cancellation) -> {
            summaryCalls.incrementAndGet();
            return Optional.of(candidate(request, "unexpected"));
        });
        StreamingModelGateway model = (request, observer, cancellation) -> {
            modelCalls.incrementAndGet();
            observer.onTextDelta("visible");
            throw new ModelGatewayException(
                    ModelGatewayException.FailureKind.CONTEXT_OVERFLOW,
                    "late context overflow");
        };

        AgentRunResult result = fixture.runtime(model, preparation)
                .run(fixture.session().id(), request("active"));

        assertThat(result.stopReason()).isEqualTo(StopReason.INCOMPLETE_MODEL_STREAM);
        assertThat(modelCalls).hasValue(1);
        assertThat(summaryCalls).hasValue(0);
        assertThat(preparation.activeRunCount()).isZero();
    }

    @Test
    void cancellationDuringPreparationPreventsGatewayCallAndClosesRun() throws Exception {
        Fixture fixture = fixtureWithHistory();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        ContextSummarizer blocking = (request, cancellation) -> {
            entered.countDown();
            await(release);
            return candidate(request, cancellation);
        };
        ContextPreparationService preparation = configured(blocking);
        AgentRuntime runtime = fixture.runtime(request -> {
            modelCalls.incrementAndGet();
            return ModelTurn.text("unexpected");
        }, preparation);
        AtomicReference<AgentRunResult> result = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> result.set(
                runtime.run(fixture.session().id(), request("active " + "a".repeat(120)))));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.cancel(
                fixture.session().id(),
                new io.github.liumaishenjian.ccjava.domain.RunId("run-2")))
                .isTrue();
        release.countDown();
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(result.get().stopReason()).isEqualTo(StopReason.USER_CANCELLED);
        assertThat(modelCalls).hasValue(0);
        assertThat(preparation.activeRunCount()).isZero();
    }

    private ContextPreparationService configured(ContextSummarizer summarizer) {
        return new ContextPreparationService(
                new ContextPreparationConfig(
                        new ContextCapacity("fake-model", 240, 20, 10),
                        40,
                        0,
                        1_000,
                        120),
                summarizer);
    }

    private Optional<SummaryCandidate> candidate(
            SummaryRequest request,
            CancellationToken cancellation) {
        if (cancellation.isCancellationRequested()) {
            return Optional.empty();
        }
        return Optional.of(candidate(request, "compact"));
    }

    private SummaryCandidate candidate(SummaryRequest request, String text) {
        return new SummaryCandidate(
                request.tier(),
                text,
                request.sourceRevision(),
                request.sourceMessageIds(),
                text.getBytes(StandardCharsets.UTF_8).length,
                Math.min(text.codePointCount(0, text.length()), request.maxOutputTokens()));
    }

    private Fixture fixtureWithHistory() {
        SequentialAgentIdGenerator ids = new SequentialAgentIdGenerator();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, AgentEventSink.noop());
        InMemorySessionStore sessions = new InMemorySessionStore(ids, lifecycle);
        ToolRegistry tools = new ToolRegistry(List.of());
        ToolExecutionPipeline pipeline = pipeline(tools, lifecycle);
        AgentSession session = sessions.create(SessionSpec.of(SYSTEM));
        new AgentRuntime(
                sessions,
                ids,
                ScriptedModelGateway.of(ModelTurn.text("history " + "h".repeat(80))),
                new DefaultContextAssembler(),
                tools,
                pipeline,
                lifecycle)
                .run(session.id(), request("old goal"));
        return new Fixture(ids, lifecycle, sessions, tools, pipeline, session);
    }

    private ToolExecutionPipeline pipeline(
            ToolRegistry tools,
            LifecycleDispatcher lifecycle) {
        return new ToolExecutionPipeline(
                tools,
                (invocation, definition) -> io.github.liumaishenjian.ccjava.domain.PermissionOutcome.of(
                        io.github.liumaishenjian.ccjava.domain.PermissionDecision.ALLOW,
                        io.github.liumaishenjian.ccjava.domain.PermissionReason.EFFECT_DEFAULT,
                        io.github.liumaishenjian.ccjava.domain.PermissionSelector.toolWide(
                                definition.name(), definition.source())),
                (invocation, definition, outcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce(),
                lifecycle);
    }

    private AgentRunRequest request(String text) {
        return new AgentRunRequest(new UserMessage(text), AgentLimits.DEFAULT);
    }

    private ToolCall call(String id) {
        return new ToolCall(id, "echo", new JsonObject(Map.of("value", id)));
    }

    private void assertCompleteToolBatches(List<AgentMessage> messages) {
        List<String> pending = new ArrayList<>();
        for (AgentMessage message : messages) {
            if (message instanceof AssistantMessage assistant && !assistant.toolCalls().isEmpty()) {
                assertThat(pending).isEmpty();
                pending.addAll(assistant.toolCalls().stream().map(ToolCall::id).toList());
            } else if (message instanceof ToolResultMessage result) {
                assertThat(pending).isNotEmpty();
                assertThat(result.result().callId()).isEqualTo(pending.removeFirst());
            } else {
                assertThat(pending).isEmpty();
            }
        }
        assertThat(pending).isEmpty();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("test boundary timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class RecordingJournal implements SessionJournal {
        private final List<AgentMessage> messages = new ArrayList<>();

        @Override
        public void runStarted(
                io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                io.github.liumaishenjian.ccjava.domain.RunId runId,
                UserMessage message) {
            messages.add(message);
        }

        @Override
        public void assistantAppended(
                io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                io.github.liumaishenjian.ccjava.domain.RunId runId,
                AssistantMessage message) {
            messages.add(message);
        }

        @Override
        public void toolResolved(
                io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                io.github.liumaishenjian.ccjava.domain.RunId runId,
                int ordinal,
                io.github.liumaishenjian.ccjava.domain.ToolResult result,
                ToolResolutionReason reason) {
        }

        @Override
        public void toolStarted(
                io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                io.github.liumaishenjian.ccjava.domain.RunId runId,
                int ordinal,
                String callId,
                String toolName,
                io.github.liumaishenjian.ccjava.domain.ToolEffect effect) {
        }

        @Override
        public void toolCompleted(
                io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                io.github.liumaishenjian.ccjava.domain.RunId runId,
                int ordinal,
                io.github.liumaishenjian.ccjava.domain.ToolResult result) {
        }

        @Override
        public void runCompleted(
                io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                io.github.liumaishenjian.ccjava.domain.RunId runId,
                StopReason stopReason) {
        }

        private List<AgentMessage> messages() {
            return List.copyOf(messages);
        }
    }

    private record Fixture(
            SequentialAgentIdGenerator ids,
            LifecycleDispatcher lifecycle,
            InMemorySessionStore sessions,
            ToolRegistry tools,
            ToolExecutionPipeline pipeline,
            AgentSession session) {

        private AgentRuntime runtime(
                ModelGateway model,
                ContextPreparationService preparation) {
            return new AgentRuntime(
                    sessions,
                    ids,
                    model,
                    new DefaultContextAssembler(),
                    tools,
                    pipeline,
                    lifecycle,
                    SessionJournal.noop(),
                    preparation);
        }
    }
}
