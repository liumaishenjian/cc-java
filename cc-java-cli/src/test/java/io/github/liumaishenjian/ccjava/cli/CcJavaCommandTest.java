package io.github.liumaishenjian.ccjava.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CcJavaCommandTest {

    @TempDir
    Path workspace;

    @Test
    void printRunsExactlyOnceWithoutOpeningOrReadingJLineAndIsPipeable() {
        CliTestFixtures.RecordingRuntimeFactory runtimeFactory =
                new CliTestFixtures.RecordingRuntimeFactory(
                        (ordinal, prompt, cancellation, listener) -> {
                            CliTestFixtures.publishDelta(listener, ordinal, 1, "hel");
                            CliTestFixtures.publishDelta(listener, ordinal, 1, "lo");
                            return AgentRunResult.completed(
                                    new SessionId("cli-session"),
                                    new RunId("run-1"),
                                    "hello",
                                    1,
                                    0);
                        });
        CliTestFixtures.ScriptedTerminal forbiddenTerminal =
                new CliTestFixtures.ScriptedTerminal(true, true, "/exit");
        CliTestFixtures.RecordingTerminalFactory terminalFactory =
                new CliTestFixtures.RecordingTerminalFactory(forbiddenTerminal);

        Execution execution = execute(
                CliTestFixtures.defaults(workspace, false),
                CliTestFixtures.environment(Map.of()),
                runtimeFactory,
                terminalFactory,
                "--print",
                "say hello");

        assertThat(execution.code()).isEqualTo(CliExitCode.SUCCESS.code());
        assertThat(execution.out()).isEqualTo("hello" + System.lineSeparator());
        assertThat(execution.out()).doesNotContain("\u001B[");
        assertThat(execution.err()).isEmpty();
        assertThat(terminalFactory.opens()).isZero();
        assertThat(forbiddenTerminal.readCount()).isZero();
        assertThat(runtimeFactory.runtime().prompts()).containsExactly("say hello");
        assertThat(runtimeFactory.runtime().closed()).isTrue();
    }

    @Test
    void noPrintOnNonInteractiveTerminalFailsWithoutCreatingRuntime() {
        CliTestFixtures.RecordingRuntimeFactory runtimeFactory =
                new CliTestFixtures.RecordingRuntimeFactory();
        CliTestFixtures.ScriptedTerminal nonInteractive =
                new CliTestFixtures.ScriptedTerminal(false, false);
        CliTestFixtures.RecordingTerminalFactory terminalFactory =
                new CliTestFixtures.RecordingTerminalFactory(nonInteractive);

        Execution execution = execute(
                CliTestFixtures.defaults(workspace, false),
                CliTestFixtures.environment(Map.of()),
                runtimeFactory,
                terminalFactory);

        assertThat(execution.code()).isEqualTo(CliExitCode.CONFIGURATION.code());
        assertThat(execution.err()).contains("不是交互终端").contains("--print");
        assertThat(nonInteractive.readCount()).isZero();
        assertThat(nonInteractive.closed()).isTrue();
        assertThat(runtimeFactory.runtime()).isNull();
    }

    @Test
    void interactiveCreatesOneRuntimeSessionAndShowsOnlySecretPresence() {
        CliTestFixtures.RecordingRuntimeFactory runtimeFactory =
                new CliTestFixtures.RecordingRuntimeFactory();
        CliTestFixtures.ScriptedTerminal terminal =
                new CliTestFixtures.ScriptedTerminal(true, true, "/exit");
        CliTestFixtures.RecordingTerminalFactory terminalFactory =
                new CliTestFixtures.RecordingTerminalFactory(terminal);
        String rawSecret = "must-never-be-rendered";

        Execution execution = execute(
                CliTestFixtures.defaults(workspace, true),
                CliTestFixtures.environment(Map.of(
                        "TEST_PROVIDER_API_KEY", rawSecret,
                        CliConfigurationResolver.MODEL_ENV, "environment-model")),
                runtimeFactory,
                terminalFactory);

        assertThat(execution.code()).isEqualTo(CliExitCode.SUCCESS.code());
        assertThat(terminalFactory.opens()).isEqualTo(1);
        assertThat(runtimeFactory.configurations()).singleElement().satisfies(configuration -> {
            assertThat(configuration.ansiEnabled()).isTrue();
            assertThat(configuration.model().value()).isEqualTo("environment-model");
            assertThat(configuration.secretStatus().present()).isTrue();
        });
        assertThat(terminal.output())
                .contains("TEST_PROVIDER_API_KEY=present")
                .doesNotContain(rawSecret);
        assertThat(runtimeFactory.runtime().closed()).isTrue();
        assertThat(terminal.closed()).isTrue();
    }

    @Test
    void missingRequiredSecretFailsBeforeTerminalOrRuntimeCreation() {
        CliTestFixtures.RecordingRuntimeFactory runtimeFactory =
                new CliTestFixtures.RecordingRuntimeFactory();
        CliTestFixtures.RecordingTerminalFactory terminalFactory =
                new CliTestFixtures.RecordingTerminalFactory(
                        new CliTestFixtures.ScriptedTerminal(true, true, "/exit"));

        Execution execution = execute(
                CliTestFixtures.defaults(workspace, true),
                CliTestFixtures.environment(Map.of()),
                runtimeFactory,
                terminalFactory,
                "--print",
                "task");

        assertThat(execution.code()).isEqualTo(CliExitCode.CONFIGURATION.code());
        assertThat(execution.err()).contains("TEST_PROVIDER_API_KEY=missing");
        assertThat(terminalFactory.opens()).isZero();
        assertThat(runtimeFactory.runtime()).isNull();
    }

    @Test
    void typedCliOverridesReachRuntimeFactoryWithCliSource() {
        CliTestFixtures.RecordingRuntimeFactory runtimeFactory =
                new CliTestFixtures.RecordingRuntimeFactory();

        Execution execution = execute(
                CliTestFixtures.defaults(workspace, false),
                CliTestFixtures.environment(Map.of(
                        CliConfigurationResolver.MODEL_ENV, "environment-model")),
                runtimeFactory,
                new CliTestFixtures.RecordingTerminalFactory(
                        new CliTestFixtures.ScriptedTerminal(true, true, "/exit")),
                "--print",
                "task",
                "--model",
                "cli-model",
                "--timeout-seconds",
                "12",
                "--max-retries",
                "0");

        assertThat(execution.code()).isEqualTo(CliExitCode.SUCCESS.code());
        assertThat(runtimeFactory.configurations()).singleElement().satisfies(configuration -> {
            assertThat(configuration.model().value()).isEqualTo("cli-model");
            assertThat(configuration.model().source())
                    .isEqualTo(CliConfiguration.Source.CLI);
            assertThat(configuration.timeout().value()).isEqualTo(Duration.ofSeconds(12));
            assertThat(configuration.maxRetries().value()).isZero();
            assertThat(configuration.ansiEnabled()).isFalse();
        });
    }

    @Test
    void picocliSyntaxErrorUsesUsageExitCodeWithoutStartingRuntime() {
        CliTestFixtures.RecordingRuntimeFactory runtimeFactory =
                new CliTestFixtures.RecordingRuntimeFactory();

        Execution execution = execute(
                CliTestFixtures.defaults(workspace, false),
                CliTestFixtures.environment(Map.of()),
                runtimeFactory,
                new CliTestFixtures.RecordingTerminalFactory(
                        new CliTestFixtures.ScriptedTerminal(true, false)),
                "--print",
                "task",
                "--timeout-seconds",
                "not-an-integer");

        assertThat(execution.code()).isEqualTo(CliExitCode.USAGE.code());
        assertThat(execution.err()).contains("--timeout-seconds");
        assertThat(runtimeFactory.runtime()).isNull();
    }

    @Test
    void helpDoesNotInitializeTerminalOrRuntime() {
        CliTestFixtures.RecordingRuntimeFactory runtimeFactory =
                new CliTestFixtures.RecordingRuntimeFactory();
        CliTestFixtures.RecordingTerminalFactory terminalFactory =
                new CliTestFixtures.RecordingTerminalFactory(
                        new CliTestFixtures.ScriptedTerminal(true, true));

        Execution execution = execute(
                CliTestFixtures.defaults(workspace, false),
                CliTestFixtures.environment(Map.of()),
                runtimeFactory,
                terminalFactory,
                "--help");

        assertThat(execution.code()).isEqualTo(CliExitCode.SUCCESS.code());
        assertThat(execution.out()).contains("Usage: cc-java");
        assertThat(terminalFactory.opens()).isZero();
        assertThat(runtimeFactory.runtime()).isNull();
    }

    private static Execution execute(
            CliDefaults defaults,
            CliEnvironment environment,
            CliRuntimeFactory runtimeFactory,
            CliTerminalFactory terminalFactory,
            String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = CcJavaCommand.commandLine(
                        defaults,
                        environment,
                        runtimeFactory,
                        terminalFactory)
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true));
        return new Execution(commandLine.execute(args), out.toString(), err.toString());
    }

    private record Execution(int code, String out, String err) {
    }
}
