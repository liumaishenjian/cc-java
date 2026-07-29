package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import java.nio.file.Path;
import java.time.Duration;
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
}
