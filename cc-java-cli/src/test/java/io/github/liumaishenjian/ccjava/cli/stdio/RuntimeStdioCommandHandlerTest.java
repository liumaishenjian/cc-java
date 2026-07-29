package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata;
import io.github.liumaishenjian.ccjava.domain.ModelUsage;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class RuntimeStdioCommandHandlerTest {

    @Test
    void terminalContainsProviderUsageAndPrivacySafeTimingProjection()
            throws Exception {
        StdioProtocolCodec codec = new StdioProtocolCodec();
        CopyOnWriteArrayList<CapturedEvent> events = new CopyOnWriteArrayList<>();
        StdioProtocol.EventEmitter emitter = (type, requestId, sessionId, runId, payload) ->
                events.add(new CapturedEvent(type, sessionId, runId, payload.deepCopy()));

        try (RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                ignored -> new ModelTurn(
                        AssistantMessage.text("COMPLETION_SENTINEL"),
                        new ModelTurnMetadata(
                                ModelFinishReason.STOP,
                                Optional.of(new ModelUsage(12, 3, 15)),
                                Optional.of("MODEL_SENTINEL"))))) {
            handler.handle(
                    codec.decodeCommand(
                            "{\"version\":0,\"type\":\"initialize\","
                                    + "\"requestId\":\"req-1\",\"sequence\":1,\"payload\":{}}"),
                    emitter);
            String sessionId = events.getFirst().sessionId().orElseThrow();
            handler.handle(
                    codec.decodeCommand(
                            ("{\"version\":0,\"type\":\"run.start\","
                                    + "\"requestId\":\"req-2\",\"sessionId\":\"%s\","
                                    + "\"sequence\":2,"
                                    + "\"payload\":{\"prompt\":\"PROMPT_SENTINEL\"}}")
                                    .formatted(sessionId)),
                    emitter);

            CapturedEvent terminal = awaitTerminal(events);
            ObjectNode telemetry = (ObjectNode) terminal.payload().get("telemetry");
            assertThat(telemetry).isNotNull();
            assertThat(telemetry.get("elapsedMillis").longValue()).isGreaterThanOrEqualTo(0);
            assertThat(telemetry.get("usageReportedTurns").intValue()).isOne();
            assertThat(telemetry.get("usageMissingTurns").intValue()).isZero();
            assertThat(telemetry.get("modelTurns").size()).isOne();
            assertThat(telemetry.get("toolCalls").isEmpty()).isTrue();
            assertThat(telemetry.get("totalUsage").get("inputTokens").longValue())
                    .isEqualTo(12);
            assertThat(telemetry.get("totalUsage").get("outputTokens").longValue())
                    .isEqualTo(3);
            assertThat(telemetry.get("totalUsage").get("totalTokens").longValue())
                    .isEqualTo(15);
            assertThat(telemetry.toString())
                    .doesNotContain(
                            "PROMPT_SENTINEL",
                            "COMPLETION_SENTINEL",
                            "MODEL_SENTINEL",
                            "finalText",
                            "apiKey",
                            "baseUrl");
            assertThat(terminal.payload().get("finalText").stringValue())
                    .isEqualTo("COMPLETION_SENTINEL");
        }
    }

    private CapturedEvent awaitTerminal(List<CapturedEvent> events)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<CapturedEvent> terminal = events.stream()
                    .filter(event -> event.type().equals("run.completed"))
                    .findFirst();
            if (terminal.isPresent()) {
                return terminal.orElseThrow();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("未收到真实 Runtime stdio 终态");
    }

    private record CapturedEvent(
            String type,
            Optional<String> sessionId,
            Optional<String> runId,
            ObjectNode payload) {
    }
}
