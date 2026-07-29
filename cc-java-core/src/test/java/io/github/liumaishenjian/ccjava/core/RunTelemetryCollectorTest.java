package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.AgentEvent;
import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata;
import io.github.liumaishenjian.ccjava.domain.ModelUsage;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RunTelemetryCollectorTest {

    private static final SessionId SESSION_ID = new SessionId("session-telemetry");
    private static final RunId RUN_ID = new RunId("run-telemetry");
    private static final Instant ORIGIN = Instant.parse("2026-07-29T00:00:00Z");

    @Test
    void derivesBoundedTimingsAndWithholdsPartialUsageAndSensitiveContent() {
        RunTelemetryCollector collector = new RunTelemetryCollector();
        ToolCall tool = new ToolCall(
                "call-secret",
                "secret-tool-name",
                new JsonObject(Map.of("argument", "TOOL_ARGUMENT_SENTINEL")));

        publish(collector, 1, 0, new LifecycleEvent.RunStarted(
                AgentRunRequest.of("PROMPT_SENTINEL")));
        publish(collector, 2, 10, new LifecycleEvent.ModelTurnStarted(1));
        publish(collector, 3, 40, new LifecycleEvent.ModelTurnCompleted(
                1,
                turn("COMPLETION_SENTINEL", Optional.of(new ModelUsage(10, 5, 15)))));
        publish(collector, 4, 45, new LifecycleEvent.BeforeTool(1, tool));
        publish(collector, 5, 70, new LifecycleEvent.AfterTool(
                1,
                ToolResult.success(tool.id(), tool.name(), "TOOL_RESULT_SENTINEL")));
        publish(collector, 6, 80, new LifecycleEvent.ModelTurnStarted(2));
        publish(collector, 7, 120, new LifecycleEvent.ModelTurnCompleted(
                2,
                turn("SECOND_COMPLETION_SENTINEL", Optional.empty())));
        publish(collector, 8, 150, new LifecycleEvent.RunFinished(
                AgentRunResult.completed(
                        SESSION_ID,
                        RUN_ID,
                        "FINAL_TEXT_SENTINEL",
                        2,
                        1)));

        RunTelemetry telemetry = collector.find(RUN_ID).orElseThrow();
        assertThat(telemetry.elapsed()).isEqualTo(Duration.ofMillis(150));
        assertThat(telemetry.modelTurns())
                .extracting(ModelTurnTelemetry::elapsed)
                .containsExactly(Duration.ofMillis(30), Duration.ofMillis(40));
        assertThat(telemetry.toolCalls())
                .extracting(ToolCallTelemetry::elapsed)
                .containsExactly(Duration.ofMillis(25));
        assertThat(telemetry.usageReportedTurns()).isEqualTo(1);
        assertThat(telemetry.usageMissingTurns()).isEqualTo(1);
        assertThat(telemetry.totalUsage()).isEmpty();
        assertThat(telemetry.toString())
                .doesNotContain(
                        "PROMPT_SENTINEL",
                        "COMPLETION_SENTINEL",
                        "TOOL_ARGUMENT_SENTINEL",
                        "TOOL_RESULT_SENTINEL",
                        "FINAL_TEXT_SENTINEL",
                        "secret-tool-name");
    }

    @Test
    void publishesTotalsOnlyWhenEveryCompletedTurnHasProviderUsage() {
        RunTelemetryCollector collector = new RunTelemetryCollector();
        publish(collector, 1, 0, new LifecycleEvent.RunStarted(
                AgentRunRequest.of("hello")));
        publish(collector, 2, 10, new LifecycleEvent.ModelTurnStarted(1));
        publish(collector, 3, 20, new LifecycleEvent.ModelTurnCompleted(
                1,
                turn("one", Optional.of(new ModelUsage(10, 4, 14)))));
        publish(collector, 4, 30, new LifecycleEvent.ModelTurnStarted(2));
        publish(collector, 5, 50, new LifecycleEvent.ModelTurnCompleted(
                2,
                turn("two", Optional.of(new ModelUsage(20, 6, 26)))));
        publish(collector, 6, 60, new LifecycleEvent.RunFinished(
                AgentRunResult.completed(SESSION_ID, RUN_ID, "done", 2, 0)));

        RunTelemetry telemetry = collector.find(RUN_ID).orElseThrow();
        assertThat(telemetry.usageReportedTurns()).isEqualTo(2);
        assertThat(telemetry.usageMissingTurns()).isZero();
        assertThat(telemetry.totalUsage())
                .contains(new TokenUsageTotals(30, 10, 40));
    }

    @Test
    void closesUnfinishedOperationAtRunBoundaryAndClampsClockRollback() {
        RunTelemetryCollector collector = new RunTelemetryCollector();
        ToolCall tool = new ToolCall("call-1", "read", JsonObject.empty());
        publish(collector, 1, 20, new LifecycleEvent.RunStarted(
                AgentRunRequest.of("hello")));
        publish(collector, 2, 30, new LifecycleEvent.ModelTurnStarted(1));
        publish(collector, 3, 40, new LifecycleEvent.BeforeTool(1, tool));
        publish(collector, 4, 10, new LifecycleEvent.RunFinished(
                AgentRunResult.stopped(
                        SESSION_ID,
                        RUN_ID,
                        io.github.liumaishenjian.ccjava.domain.StopReason.MODEL_ERROR,
                        1,
                        1)));

        RunTelemetry telemetry = collector.find(RUN_ID).orElseThrow();
        assertThat(telemetry.elapsed()).isZero();
        assertThat(telemetry.modelTurns()).singleElement().satisfies(turn -> {
            assertThat(turn.completed()).isFalse();
            assertThat(turn.elapsed()).isZero();
            assertThat(turn.usage()).isEmpty();
        });
        assertThat(telemetry.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.completed()).isFalse();
            assertThat(call.elapsed()).isZero();
        });
    }

    private static ModelTurn turn(String text, Optional<ModelUsage> usage) {
        return new ModelTurn(
                new AssistantMessage(text, List.of()),
                new ModelTurnMetadata(
                        ModelFinishReason.STOP,
                        usage,
                        Optional.of("model-name-not-exported")));
    }

    private static void publish(
            RunTelemetryCollector collector,
            long sequence,
            long millis,
            AgentEvent event) {
        collector.publish(new AgentEventEnvelope(
                sequence,
                ORIGIN.plusMillis(millis),
                SESSION_ID,
                Optional.of(RUN_ID),
                event));
    }
}
