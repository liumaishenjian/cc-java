package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.core.RunTelemetry;
import io.github.liumaishenjian.ccjava.core.TokenUsageTotals;
import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata;
import io.github.liumaishenjian.ccjava.domain.ModelUsage;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class HeadlessRuntimeSessionTest {

    @Test
    void runsDeterministicModelThroughTheRealAgentRuntime() {
        ModelGateway model = ignored -> ModelTurn.text("hello from runtime");

        AgentRunResult result;
        try (HeadlessRuntimeSession application =
                     new HeadlessRuntimeSession(model, AgentEventSink.noop())) {
            application.open();
            result = application.run("hello");
        }

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(result.finalText()).contains("hello from runtime");
        assertThat(result.modelTurns()).isOne();
        assertThat(result.toolCalls()).isZero();
    }

    @Test
    void rejectsBlankAndOversizedPromptsBeforeCallingTheModel() {
        ModelGateway model = ignored -> {
            throw new AssertionError("非法 Prompt 不应调用 ModelGateway");
        };

        try (HeadlessRuntimeSession application =
                     new HeadlessRuntimeSession(model, AgentEventSink.noop())) {
            application.open();

            assertThatThrownBy(() -> application.run("  "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> application.run(
                    "x".repeat(HeadlessRuntimeSession.MAX_PROMPT_CHARS + 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void recordsTypedOverridesInSessionMetadata() {
        CopyOnWriteArrayList<AgentEventEnvelope> events = new CopyOnWriteArrayList<>();
        Path workspace = Path.of("").toAbsolutePath().normalize();
        HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(
                workspace,
                "override-model",
                Duration.ofSeconds(3));

        try (HeadlessRuntimeSession application = new HeadlessRuntimeSession(
                ignored -> ModelTurn.text("done"),
                events::add,
                options)) {
            application.open();
            application.run("hello");
        }

        assertThat(events)
                .extracting(AgentEventEnvelope::event)
                .filteredOn(LifecycleEvent.SessionStarted.class::isInstance)
                .singleElement()
                .satisfies(event -> {
                    LifecycleEvent.SessionStarted started =
                            (LifecycleEvent.SessionStarted) event;
                    assertThat(started.spec().runtimeMetadata())
                            .containsEntry("workspace", workspace.toString())
                            .containsEntry("model", "override-model")
                            .containsEntry("timeout", "PT3S");
                });
    }

    @Test
    void keepsCanonicalHistoryAcrossTwoRunsInOneHeadlessSession() {
        CopyOnWriteArrayList<ModelRequest> requests = new CopyOnWriteArrayList<>();
        ModelGateway model = request -> {
            requests.add(request);
            return ModelTurn.text(requests.size() == 1 ? "first answer" : "second answer");
        };

        try (HeadlessRuntimeSession application =
                     new HeadlessRuntimeSession(model, AgentEventSink.noop())) {
            application.open();
            application.run("first question");
            application.run("second question");
        }

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).sessionId()).isEqualTo(requests.get(1).sessionId());
        assertThat(requests.get(0).runId()).isNotEqualTo(requests.get(1).runId());
        assertThat(requests.get(0).turnNumber()).isOne();
        assertThat(requests.get(1).turnNumber()).isOne();
        assertThat(requests.get(1).messages())
                .extracting(message -> switch (message) {
                    case SystemMessage ignored -> "system";
                    case UserMessage user -> "user:" + user.content();
                    case AssistantMessage assistant -> "assistant:" + assistant.text();
                    default -> message.getClass().getSimpleName();
                })
                .containsExactly(
                        "system",
                        "user:first question",
                        "assistant:first answer",
                        "user:second question");
    }

    @Test
    void exposesOnlyProviderReportedUsageThroughRunTelemetry() {
        ModelGateway model = ignored -> new ModelTurn(
                AssistantMessage.text("answer"),
                new ModelTurnMetadata(
                        ModelFinishReason.STOP,
                        Optional.of(new ModelUsage(12, 3, 15)),
                        Optional.of("provider-model")));

        try (HeadlessRuntimeSession application =
                     new HeadlessRuntimeSession(model, AgentEventSink.noop())) {
            application.open();
            AgentRunResult result = application.run("private prompt");

            RunTelemetry telemetry = application.telemetry(result.runId()).orElseThrow();
            assertThat(telemetry.totalUsage())
                    .contains(new TokenUsageTotals(12, 3, 15));
            assertThat(telemetry.toString())
                    .doesNotContain("private prompt", "answer", "provider-model");
        }
    }
}
