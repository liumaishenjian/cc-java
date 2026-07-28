package io.github.liumaishenjian.ccjava.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.ModelTextDelta;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TerminalRendererTest {

    private static final SessionId SESSION_ID = new SessionId("renderer-session");
    private static final RunId RUN_ID = new RunId("renderer-run");

    @Test
    void rendersOrderedDeltasExactlyOnceAndDoesNotRepeatAggregateText() {
        StringWriter assistant = new StringWriter();
        StringWriter status = new StringWriter();
        TerminalRenderer renderer = renderer(assistant, status, false);
        renderer.beginRun();

        renderer.onAgentEvent(envelope(1, new ModelTextDelta(1, "hel")));
        renderer.onAgentEvent(envelope(2, new ModelTextDelta(1, "lo")));
        renderer.completeRun(AgentRunResult.completed(
                SESSION_ID,
                RUN_ID,
                "hello",
                1,
                0));

        assertThat(assistant.toString()).isEqualTo("hello" + System.lineSeparator());
        assertThat(status.toString()).isEmpty();
    }

    @Test
    void fallsBackToAggregatedFinalTextWhenProviderPublishedNoDelta() {
        StringWriter assistant = new StringWriter();
        TerminalRenderer renderer = renderer(assistant, new StringWriter(), false);
        renderer.beginRun();

        renderer.completeRun(AgentRunResult.completed(
                SESSION_ID,
                RUN_ID,
                "aggregated",
                1,
                0));

        assertThat(assistant.toString())
                .isEqualTo("aggregated" + System.lineSeparator());
    }

    @Test
    void fallsBackToFinalTextWhenOnlyAnEarlierToolTurnPublishedDelta() {
        StringWriter assistant = new StringWriter();
        TerminalRenderer renderer = renderer(assistant, new StringWriter(), false);
        renderer.beginRun();

        renderer.onAgentEvent(envelope(
                1,
                new LifecycleEvent.ModelTurnStarted(1)));
        renderer.onAgentEvent(envelope(
                2,
                new ModelTextDelta(1, "checking tool")));
        renderer.onAgentEvent(envelope(
                3,
                new LifecycleEvent.ModelTurnStarted(2)));
        renderer.completeRun(AgentRunResult.completed(
                SESSION_ID,
                RUN_ID,
                "final answer",
                2,
                1));

        assertThat(assistant.toString())
                .isEqualTo(
                        "checking tool"
                                + System.lineSeparator()
                                + "final answer"
                                + System.lineSeparator());
    }

    @Test
    void plainAssistantOutputRemovesTerminalSequencesAndNormalizesNewlines() {
        StringWriter assistant = new StringWriter();
        TerminalRenderer renderer = renderer(assistant, new StringWriter(), false);
        renderer.beginRun();

        renderer.onAgentEvent(envelope(
                1,
                new ModelTextDelta(
                        1,
                        "safe"
                                + "\u001B[31mred\u001B[0m"
                                + "\u001B]0;owned\u0007"
                                + "\u009B32mgreen\u009B0m"
                                + "A\rB\r\nC\nD\tE\u0000\u0008\u007F")));
        renderer.completeRun(AgentRunResult.completed(
                SESSION_ID,
                RUN_ID,
                "ignored aggregate",
                1,
                0));

        assertThat(assistant.toString())
                .isEqualTo(
                        "saferedgreenA\nB\nC\nD\tE"
                                + System.lineSeparator())
                .doesNotContain("\u001B", "\u009B", "\u0007", "\u0000", "owned");
    }

    @Test
    void plainFinalTextFallbackUsesTheSameTerminalSanitizationContract() {
        StringWriter assistant = new StringWriter();
        TerminalRenderer renderer = renderer(assistant, new StringWriter(), false);
        renderer.beginRun();

        renderer.completeRun(AgentRunResult.completed(
                SESSION_ID,
                RUN_ID,
                "answer\u001B[2J"
                        + "\u001B]8;;https://example.invalid\u0007"
                        + "link"
                        + "\u001B]8;;\u0007"
                        + "\rnext\tcolumn",
                1,
                0));

        assertThat(assistant.toString())
                .isEqualTo(
                        "answerlink\nnext\tcolumn"
                                + System.lineSeparator())
                .doesNotContain(
                        "\u001B",
                        "\u0007",
                        "https://example.invalid");
    }

    @Test
    void plainRendererContainsNoAnsiAndDoesNotExposeToolArgumentsOrOutput() {
        StringWriter assistant = new StringWriter();
        StringWriter status = new StringWriter();
        TerminalRenderer renderer = renderer(assistant, status, false);
        ToolCall call = new ToolCall(
                "call-1",
                "safe_tool",
                new JsonObject(Map.of("secret", "argument-secret")));
        ToolResult result = ToolResult.success(
                "call-1",
                "safe_tool",
                "result-secret");

        renderer.onAgentEvent(envelope(
                1,
                new LifecycleEvent.ModelTurnStarted(1)));
        renderer.onAgentEvent(envelope(
                2,
                new LifecycleEvent.BeforeTool(1, call)));
        renderer.onAgentEvent(envelope(
                3,
                new LifecycleEvent.AfterTool(1, result)));

        assertThat(status.toString())
                .contains("[model] turn 1 started")
                .contains("[tool] safe_tool requested")
                .contains("[tool] safe_tool success")
                .doesNotContain("\u001B[")
                .doesNotContain("argument-secret")
                .doesNotContain("result-secret");
    }

    @Test
    void statusOutputSanitizesToolNamesAndErrorsAndRemainsOneLinePerStatus() {
        StringWriter status = new StringWriter();
        TerminalRenderer renderer = renderer(new StringWriter(), status, false);
        ToolCall call = new ToolCall(
                "call-1",
                "safe\u001B[31mtool\u001B[0m\n[forged]\tname",
                new JsonObject(Map.of()));

        renderer.onAgentEvent(envelope(
                1,
                new LifecycleEvent.BeforeTool(1, call)));
        renderer.error(
                "failed\u001B]0;owned\u0007\r\n[forged]\tmessage\u0000");

        assertThat(status.toString())
                .isEqualTo(
                        "[tool] safetool [forged] name requested"
                                + System.lineSeparator()
                                + "[error] failed [forged] message"
                                + System.lineSeparator())
                .doesNotContain("\u001B", "\u0007", "\u0000", "owned");
    }

    @Test
    void ansiRendererKeepsOnlyRendererOwnedColorSequences() {
        StringWriter status = new StringWriter();
        TerminalRenderer renderer = renderer(new StringWriter(), status, true);
        ToolCall call = new ToolCall(
                "call-1",
                "safe\u001B[31mtool\u001B[0m",
                new JsonObject(Map.of()));

        renderer.onAgentEvent(envelope(
                1,
                new LifecycleEvent.BeforeTool(1, call)));

        assertThat(status.toString())
                .isEqualTo(
                        "\u001B[33m"
                                + "[tool] safetool requested"
                                + "\u001B[0m"
                                + System.lineSeparator())
                .doesNotContain("\u001B[31m");
    }

    @Test
    void stoppedRunUsesStatusChannelAndKeepsAssistantPipeClean() {
        StringWriter assistant = new StringWriter();
        StringWriter status = new StringWriter();
        TerminalRenderer renderer = renderer(assistant, status, false);
        renderer.beginRun();

        renderer.completeRun(AgentRunResult.stopped(
                SESSION_ID,
                RUN_ID,
                io.github.liumaishenjian.ccjava.domain.StopReason.USER_CANCELLED,
                1,
                0));

        assertThat(assistant.toString()).isEmpty();
        assertThat(status.toString()).contains("[run] user_cancelled");
    }

    private static TerminalRenderer renderer(
            StringWriter assistant,
            StringWriter status,
            boolean ansiEnabled) {
        return new TerminalRenderer(
                new PrintWriter(assistant, true),
                new PrintWriter(status, true),
                ansiEnabled);
    }

    private static AgentEventEnvelope envelope(
            long sequence,
            io.github.liumaishenjian.ccjava.domain.AgentEvent event) {
        return new AgentEventEnvelope(
                sequence,
                Instant.parse("2026-07-28T00:00:00Z"),
                SESSION_ID,
                Optional.of(RUN_ID),
                event);
    }
}
