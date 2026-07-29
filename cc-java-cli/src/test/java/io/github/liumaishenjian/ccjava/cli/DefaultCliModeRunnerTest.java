package io.github.liumaishenjian.ccjava.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultCliModeRunnerTest {

    @Test
    void mapsDeadlineOutputLimitAndUserCancellationToStableExitCodes() {
        StringWriter errors = new StringWriter();
        PrintWriter errorWriter = new PrintWriter(errors, true);
        SessionId sessionId = new SessionId("session-1");
        RunId runId = new RunId("run-1");

        int timeout = DefaultCliModeRunner.exitCode(
                AgentRunResult.stopped(
                        sessionId,
                        runId,
                        StopReason.TIME_LIMIT_REACHED,
                        1,
                        0),
                errorWriter);
        int cancelled = DefaultCliModeRunner.exitCode(
                AgentRunResult.stopped(
                        sessionId,
                        runId,
                        StopReason.USER_CANCELLED,
                        1,
                        0),
                errorWriter);
        int outputLimit = DefaultCliModeRunner.exitCode(
                AgentRunResult.stopped(
                        sessionId,
                        runId,
                        StopReason.OUTPUT_LIMIT_REACHED,
                        1,
                        0),
                errorWriter);

        assertThat(timeout).isEqualTo(CliExitCode.RUNTIME_FAILURE);
        assertThat(cancelled).isEqualTo(CliExitCode.USER_CANCELLED);
        assertThat(outputLimit).isEqualTo(CliExitCode.RUNTIME_FAILURE);
        assertThat(errors.toString()).isEqualTo(
                "cc-java: run timed out" + System.lineSeparator()
                        + "cc-java: output limit reached" + System.lineSeparator());
    }

    @Test
    void rejectsMissingWorkspaceBeforeReadingProviderConfiguration() {
        StringWriter errors = new StringWriter();
        DefaultCliModeRunner runner = new DefaultCliModeRunner(
                Path.of("").toAbsolutePath(),
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(),
                new PrintWriter(new StringWriter(), true),
                new PrintWriter(errors, true));
        Path missing = Path.of(
                "target",
                "missing-workspace-" + UUID.randomUUID()).toAbsolutePath();
        CliOverrides overrides = new CliOverrides(
                missing,
                Optional.empty(),
                Duration.ofSeconds(1));

        int exitCode = runner.runPrint("hello", overrides);

        assertThat(exitCode).isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(errors.toString()).contains("workspace is not an accessible directory");
    }
}
