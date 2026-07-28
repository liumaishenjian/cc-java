package io.github.liumaishenjian.ccjava.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveSessionTest {

    @TempDir
    Path workspace;

    @Test
    void ctrlCCancelsActiveRunAndSameSessionContinuesWithNextPrompt() throws Exception {
        CountDownLatch firstRunStarted = new CountDownLatch(1);
        CountDownLatch cancellationObserved = new CountDownLatch(1);
        CliTestFixtures.RecordingRuntimeFactory factory =
                new CliTestFixtures.RecordingRuntimeFactory(
                        (ordinal, prompt, cancellation, listener) -> {
                            CliTestFixtures.publishDelta(listener, ordinal, 1, "partial");
                            try (CancellationToken.Registration ignored =
                                    cancellation.onCancellation(
                                            cancellationObserved::countDown)) {
                                firstRunStarted.countDown();
                                await(cancellationObserved);
                            }
                            return AgentRunResult.stopped(
                                    new SessionId("cli-session"),
                                    new RunId("run-1"),
                                    StopReason.USER_CANCELLED,
                                    1,
                                    0);
                        },
                        (ordinal, prompt, cancellation, listener) -> {
                            CliTestFixtures.publishDelta(
                                    listener,
                                    ordinal,
                                    1,
                                    "second-done");
                            return AgentRunResult.completed(
                                    new SessionId("cli-session"),
                                    new RunId("run-2"),
                                    "second-done",
                                    1,
                                    0);
                        });
        CliTestFixtures.ScriptedTerminal terminal =
                new CliTestFixtures.ScriptedTerminal(
                        true,
                        false,
                        "first prompt",
                        "second prompt",
                        "/exit");
        TerminalRenderer renderer = new TerminalRenderer(
                terminal.writer(),
                terminal.writer(),
                false);
        CliRuntime runtime = factory.create(
                configuration(),
                CliTestFixtures.environment(Map.of()),
                renderer);
        InteractiveSession session = new InteractiveSession(runtime, terminal, renderer);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> result = executor.submit(session::run);
            assertThat(firstRunStarted.await(5, TimeUnit.SECONDS)).isTrue();

            terminal.triggerInterrupt();

            assertThat(result.get(5, TimeUnit.SECONDS))
                    .isEqualTo(CliExitCode.SUCCESS.code());
        } finally {
            executor.shutdownNow();
            runtime.close();
        }

        assertThat(factory.runtime().sessionId()).isEqualTo(new SessionId("cli-session"));
        assertThat(factory.runtime().prompts())
                .containsExactly("first prompt", "second prompt");
        assertThat(cancellationObserved.getCount()).isZero();
        assertThat(terminal.hasInterruptHandler()).isFalse();
        assertThat(terminal.output())
                .contains("partial")
                .contains("[run] user_cancelled")
                .contains("second-done");
    }

    @Test
    void idleCtrlCOnlyClearsInputAndDoesNotCreateRun() throws Exception {
        CliTestFixtures.RecordingRuntimeFactory factory =
                new CliTestFixtures.RecordingRuntimeFactory();
        CliTestFixtures.ScriptedTerminal terminal =
                new CliTestFixtures.ScriptedTerminal(
                        true,
                        false,
                        CliTestFixtures.ScriptedTerminal.Signal.USER_INTERRUPT,
                        "real prompt",
                        "/exit");
        TerminalRenderer renderer = new TerminalRenderer(
                terminal.writer(),
                terminal.writer(),
                false);
        CliRuntime runtime = factory.create(
                configuration(),
                CliTestFixtures.environment(Map.of()),
                renderer);
        try {
            int exitCode = new InteractiveSession(runtime, terminal, renderer).run();

            assertThat(exitCode).isEqualTo(CliExitCode.SUCCESS.code());
        } finally {
            runtime.close();
        }

        assertThat(factory.runtime().prompts()).containsExactly("real prompt");
        assertThat(terminal.readCount()).isEqualTo(3);
    }

    @Test
    void eofEndsSessionWithoutStartingAdditionalRun() throws Exception {
        CliTestFixtures.RecordingRuntimeFactory factory =
                new CliTestFixtures.RecordingRuntimeFactory();
        CliTestFixtures.ScriptedTerminal terminal =
                new CliTestFixtures.ScriptedTerminal(
                        true,
                        false,
                        CliTestFixtures.ScriptedTerminal.Signal.END_OF_INPUT);
        TerminalRenderer renderer = new TerminalRenderer(
                terminal.writer(),
                new PrintWriter(new StringWriter(), true),
                false);
        CliRuntime runtime = factory.create(
                configuration(),
                CliTestFixtures.environment(Map.of()),
                renderer);
        try {
            assertThat(new InteractiveSession(runtime, terminal, renderer).run())
                    .isEqualTo(CliExitCode.SUCCESS.code());
        } finally {
            runtime.close();
        }

        assertThat(factory.runtime().prompts()).isEmpty();
    }

    private CliConfiguration configuration() throws CliConfigurationException {
        return new CliConfigurationResolver(
                CliTestFixtures.defaults(workspace, false),
                CliTestFixtures.environment(Map.of()))
                .resolve(new CliOverrides(null, null, null, null, true));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("未观察到取消");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("测试线程被中断", exception);
        }
    }
}
