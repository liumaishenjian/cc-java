package io.github.liumaishenjian.ccjava.cli;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * 定义 Java Headless 的稳定命令行契约。
 *
 * <p>交互式终端由 React/Ink 提供，因此本命令只允许一次性 Print 或内部 stdio
 * 二选一。该类型不创建模型、不执行 Agent Loop，也不解释 Runtime 终态。</p>
 *
 * @since 0.1.0
 */
@Command(
        name = "cc-java",
        mixinStandardHelpOptions = true,
        version = "cc-java 0.1.0-SNAPSHOT",
        description = "Java Headless coding-agent runtime")
final class CcJavaCommand implements Callable<Integer> {

    private final CliModeRunner runner;

    @ArgGroup(exclusive = true, multiplicity = "1")
    private Mode mode;

    @Option(
            names = "--workspace",
            paramLabel = "<path>",
            description = "Workspace 目录；默认当前目录")
    private Path workspace = Path.of("");

    @Option(
            names = "--model",
            paramLabel = "<name>",
            description = "覆盖本次进程使用的模型名；不接受 API Key")
    private String model;

    @Option(
            names = "--timeout",
            paramLabel = "<duration>",
            converter = CliDurationConverter.class,
            description = "每个 Run 的墙钟限制，例如 250ms、30s、5m；默认 5m")
    private Duration timeout = CliOverrides.DEFAULT_TIMEOUT;

    @Spec
    private CommandSpec commandSpec;

    CcJavaCommand(CliModeRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner 不能为空");
    }

    @Override
    public Integer call() {
        CliOverrides overrides;
        try {
            overrides = new CliOverrides(
                    workspace,
                    Optional.ofNullable(model),
                    timeout);
        } catch (IllegalArgumentException exception) {
            throw new ParameterException(
                    commandSpec.commandLine(),
                    exception.getMessage());
        }
        if (mode.printPrompt != null) {
            return runner.runPrint(mode.printPrompt, overrides);
        }
        return runner.runStdio(overrides);
    }

    private static final class Mode {

        @Option(
                names = "--print",
                paramLabel = "<prompt>",
                description = "执行一次 Agent Run，并把 Assistant 文本写到 stdout")
        private String printPrompt;

        @Option(
                names = "--stdio",
                description = "启动供 React/Ink TUI 使用的内部 NDJSON stdio v0")
        private boolean stdio;
    }
}
