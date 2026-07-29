package io.github.liumaishenjian.ccjava.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CcJavaCommandTest {

    @Test
    void dispatchesPrintPromptAndReturnsRunnerExitCode() {
        FakeCliModeRunner runner = new FakeCliModeRunner(17, 18);
        Invocation invocation = execute(runner, "--print", "介绍一下你自己");

        assertThat(invocation.exitCode()).isEqualTo(17);
        assertThat(runner.printPrompt).isEqualTo("介绍一下你自己");
        assertThat(runner.overrides.model()).isEmpty();
        assertThat(runner.overrides.timeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(runner.stdioCalls).isZero();
    }

    @Test
    void dispatchesStdioAndReturnsRunnerExitCode() {
        FakeCliModeRunner runner = new FakeCliModeRunner(17, 18);
        Invocation invocation = execute(runner, "--stdio");

        assertThat(invocation.exitCode()).isEqualTo(18);
        assertThat(runner.printPrompt).isNull();
        assertThat(runner.stdioCalls).isOne();
    }

    @Test
    void parsesWorkspaceModelAndHumanDurationAsTypedOverrides() {
        FakeCliModeRunner runner = new FakeCliModeRunner(0, 0);

        Invocation invocation = execute(
                runner,
                "--workspace",
                ".",
                "--model",
                "override-model",
                "--timeout",
                "250ms",
                "--print",
                "hello");

        assertThat(invocation.exitCode()).isZero();
        assertThat(runner.overrides.workspace())
                .isEqualTo(Path.of("").toAbsolutePath().normalize());
        assertThat(runner.overrides.model()).contains("override-model");
        assertThat(runner.overrides.timeout()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void rejectsInvalidTimeoutAndModelBeforeCallingRunner() {
        FakeCliModeRunner runner = new FakeCliModeRunner(0, 0);

        Invocation malformedTimeout = execute(
                runner,
                "--timeout",
                "soon",
                "--print",
                "hello");
        Invocation outOfRangeTimeout = execute(
                runner,
                "--timeout",
                "1ms",
                "--print",
                "hello");
        Invocation blankModel = execute(
                runner,
                "--model",
                " ",
                "--print",
                "hello");

        assertThat(malformedTimeout.exitCode())
                .isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(outOfRangeTimeout.exitCode())
                .isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(blankModel.exitCode())
                .isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(runner.printPrompt).isNull();
        assertThat(runner.stdioCalls).isZero();
    }

    @Test
    void rejectsMissingOrConflictingModeWithoutCallingRunner() {
        FakeCliModeRunner runner = new FakeCliModeRunner(0, 0);

        Invocation missing = execute(runner);
        Invocation conflicting = execute(
                runner,
                "--print",
                "hello",
                "--stdio");

        assertThat(missing.exitCode()).isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(conflicting.exitCode()).isEqualTo(CliExitCode.USAGE_OR_CONFIGURATION);
        assertThat(runner.printPrompt).isNull();
        assertThat(runner.stdioCalls).isZero();
    }

    @Test
    void standardHelpDoesNotRequireAHeadlessMode() {
        FakeCliModeRunner runner = new FakeCliModeRunner(0, 0);

        Invocation invocation = execute(runner, "--help");

        assertThat(invocation.exitCode()).isZero();
        assertThat(invocation.stdout())
                .contains("Usage: cc-java")
                .contains("--print")
                .contains("--stdio");
        assertThat(runner.printPrompt).isNull();
        assertThat(runner.stdioCalls).isZero();
    }

    private Invocation execute(FakeCliModeRunner runner, String... args) {
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exitCode = CcJavaCliMain.execute(
                args,
                runner,
                new PrintWriter(stdout, true),
                new PrintWriter(stderr, true));
        return new Invocation(exitCode, stdout.toString(), stderr.toString());
    }

    private record Invocation(int exitCode, String stdout, String stderr) {
    }

    private static final class FakeCliModeRunner implements CliModeRunner {

        private final int printExitCode;
        private final int stdioExitCode;
        private String printPrompt;
        private CliOverrides overrides;
        private int stdioCalls;

        private FakeCliModeRunner(int printExitCode, int stdioExitCode) {
            this.printExitCode = printExitCode;
            this.stdioExitCode = stdioExitCode;
        }

        @Override
        public int runPrint(String prompt, CliOverrides overrides) {
            printPrompt = prompt;
            this.overrides = overrides;
            return printExitCode;
        }

        @Override
        public int runStdio(CliOverrides overrides) {
            this.overrides = overrides;
            stdioCalls++;
            return stdioExitCode;
        }
    }
}
