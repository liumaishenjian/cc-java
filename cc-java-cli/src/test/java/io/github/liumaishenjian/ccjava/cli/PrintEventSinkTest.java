package io.github.liumaishenjian.ccjava.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.ModelTextDelta;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PrintEventSinkTest {

    @Test
    void writesStreamingTextExactlyOnceAndAddsFinalNewline() {
        StringWriter output = new StringWriter();
        PrintEventSink sink = new PrintEventSink(new PrintWriter(output, true));
        SessionId sessionId = new SessionId("session-1");
        RunId runId = new RunId("run-1");

        sink.publish(envelope(1, sessionId, runId, "你好，"));
        sink.publish(envelope(2, sessionId, runId, "Java Agent"));
        sink.finish(AgentRunResult.completed(
                sessionId,
                runId,
                "你好，Java Agent",
                1,
                0));

        assertThat(output.toString()).isEqualTo("你好，Java Agent" + System.lineSeparator());
    }

    @Test
    void fallsBackToAggregatedFinalTextForNonStreamingGateway() {
        StringWriter output = new StringWriter();
        PrintEventSink sink = new PrintEventSink(new PrintWriter(output, true));
        SessionId sessionId = new SessionId("session-1");
        RunId runId = new RunId("run-1");

        sink.finish(AgentRunResult.completed(
                sessionId,
                runId,
                "aggregated",
                1,
                0));

        assertThat(output.toString()).isEqualTo("aggregated" + System.lineSeparator());
    }

    private AgentEventEnvelope envelope(
            long sequence,
            SessionId sessionId,
            RunId runId,
            String text) {
        return new AgentEventEnvelope(
                sequence,
                Instant.EPOCH,
                sessionId,
                Optional.of(runId),
                new ModelTextDelta(1, text));
    }
}
