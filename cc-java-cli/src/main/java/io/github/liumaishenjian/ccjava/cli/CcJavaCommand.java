package io.github.liumaishenjian.ccjava.cli;

import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * 解析 cc-java S02 参数并选择 Interactive 或 Print 模式。
 *
 * <p>命令对象返回退出码而不调用 {@link System#exit(int)}，从而可在普通 JUnit 中
 * 完整验证。只有 {@link CcJavaMain} 负责进程终止。</p>
 *
 * @since 0.1.0
 */
@Command(
        name = "cc-java",
        mixinStandardHelpOptions = true,
        description = "Java Coding Agent Runtime 与 CLI",
        exitCodeOnInvalidInput = 2,
        exitCodeOnExecutionException = 70)
public final class CcJavaCommand implements Callable<Integer> {

    private final CliDefaults defaults;
    private final CliEnvironment environment;
    private final CliRuntimeFactory runtimeFactory;
    private final CliTerminalFactory terminalFactory;

    @Spec
    private CommandSpec commandSpec;

    @Option(
            names = "--print",
            paramLabel = "<prompt>",
            description = "执行一次非交互任务并返回退出码")
    private String printPrompt;

    @Option(
            names = "--workspace",
            paramLabel = "<path>",
            description = "设置当前 Workspace")
    private Path workspace;

    @Option(
            names = "--model",
            paramLabel = "<name>",
            description = "覆盖默认模型")
    private String model;

    @Option(
            names = "--ollama-base-url",
            paramLabel = "<url>",
            description = "覆盖 Ollama HTTP(S) 根地址")
    private URI ollamaBaseUrl;

    @Option(
            names = "--max-output-tokens",
            paramLabel = "<count>",
            description = "设置单个模型回合的输出 Token 上限")
    private Integer maxOutputTokens;

    @Option(
            names = "--timeout-seconds",
            paramLabel = "<seconds>",
            description = "设置单个 Run 的正整数超时")
    private Integer timeoutSeconds;

    @Option(
            names = "--max-retries",
            paramLabel = "<count>",
            description = "设置 0..3 次可重试模型错误恢复")
    private Integer maxRetries;

    @Option(
            names = "--no-color",
            description = "禁用 ANSI 颜色")
    private boolean noColor;

    /**
     * 创建可注入依赖的 Picocli 命令。
     *
     * @param defaults        Provider 与 Runtime 默认值
     * @param environment     环境变量边界
     * @param runtimeFactory  Runtime Composition Root
     * @param terminalFactory JLine Terminal 工厂
     */
    public CcJavaCommand(
            CliDefaults defaults,
            CliEnvironment environment,
            CliRuntimeFactory runtimeFactory,
            CliTerminalFactory terminalFactory) {
        this.defaults = Objects.requireNonNull(defaults, "defaults 不能为空");
        this.environment = Objects.requireNonNull(environment, "environment 不能为空");
        this.runtimeFactory = Objects.requireNonNull(
                runtimeFactory,
                "runtimeFactory 不能为空");
        this.terminalFactory = Objects.requireNonNull(
                terminalFactory,
                "terminalFactory 不能为空");
    }

    @Override
    public Integer call() {
        PrintWriter out = commandSpec.commandLine().getOut();
        PrintWriter err = commandSpec.commandLine().getErr();
        CliConfiguration configuration;
        try {
            configuration = new CliConfigurationResolver(defaults, environment)
                    .resolve(new CliOverrides(
                            workspace,
                            model,
                            ollamaBaseUrl,
                            maxOutputTokens,
                            timeoutSeconds,
                            maxRetries,
                            noColor));
        } catch (CliConfigurationException exception) {
            plainError(err, exception.getMessage());
            return CliExitCode.CONFIGURATION.code();
        }

        if (configuration.secretStatus().required()
                && !configuration.secretStatus().present()) {
            plainError(
                    err,
                    configuration.secretStatus().environmentVariable() + "=missing");
            return CliExitCode.CONFIGURATION.code();
        }

        if (printPrompt != null) {
            return runPrint(configuration.withAnsiEnabled(false), out, err);
        }
        return runInteractive(configuration, err);
    }

    private int runPrint(
            CliConfiguration configuration,
            PrintWriter out,
            PrintWriter err) {
        TerminalRenderer renderer = new TerminalRenderer(out, err, false);
        try (CliRuntime runtime =
                runtimeFactory.create(configuration, environment, renderer)) {
            return new PrintSession(runtime, renderer).run(printPrompt);
        } catch (CliStartupException exception) {
            renderer.error(exception.getMessage());
            return exception.exitCode().code();
        } catch (RuntimeException exception) {
            renderer.error("CLI Composition Root 启动失败");
            return CliExitCode.INTERNAL_ERROR.code();
        }
    }

    private int runInteractive(
            CliConfiguration baseConfiguration,
            PrintWriter err) {
        CliTerminal terminal;
        try {
            terminal = terminalFactory.open(noColor);
        } catch (CliStartupException exception) {
            plainError(err, exception.getMessage());
            return exception.exitCode().code();
        }

        try (terminal) {
            if (!terminal.interactive()) {
                plainError(
                        err,
                        "当前 stdin/stdout 不是交互终端；请使用 --print");
                return CliExitCode.CONFIGURATION.code();
            }

            CliConfiguration configuration = baseConfiguration.withAnsiEnabled(
                    terminal.ansiSupported() && !noColor);
            TerminalRenderer renderer = new TerminalRenderer(
                    terminal.writer(),
                    terminal.writer(),
                    configuration.ansiEnabled());
            renderer.renderConfiguration(configuration);
            try (CliRuntime runtime =
                    runtimeFactory.create(configuration, environment, renderer)) {
                return new InteractiveSession(runtime, terminal, renderer).run();
            } catch (CliStartupException exception) {
                renderer.error(exception.getMessage());
                return exception.exitCode().code();
            } catch (RuntimeException exception) {
                renderer.error("CLI Composition Root 启动失败");
                return CliExitCode.INTERNAL_ERROR.code();
            }
        }
    }

    private void plainError(PrintWriter err, String message) {
        err.print("[error] ");
        err.println(message);
        err.flush();
    }

    /**
     * 创建注入式 Picocli 实例，供 Main 与测试共用。
     *
     * @param defaults        Provider 默认值
     * @param environment     环境变量边界
     * @param runtimeFactory  Runtime 工厂
     * @param terminalFactory Terminal 工厂
     * @return 已配置命令行
     */
    public static CommandLine commandLine(
            CliDefaults defaults,
            CliEnvironment environment,
            CliRuntimeFactory runtimeFactory,
            CliTerminalFactory terminalFactory) {
        return new CommandLine(new CcJavaCommand(
                defaults,
                environment,
                runtimeFactory,
                terminalFactory));
    }
}
