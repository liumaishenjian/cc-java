package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import io.github.liumaishenjian.ccjava.cli.session.SessionStorage;
import io.github.liumaishenjian.ccjava.cli.stdio.RuntimeStdioCommandHandler;
import io.github.liumaishenjian.ccjava.cli.stdio.StdioProtocolServer;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.model.springai.config.OpenAiCompatibleSettings;
import io.github.liumaishenjian.ccjava.model.springai.config.ProviderConfigurationException;
import io.github.liumaishenjian.ccjava.model.springai.config.ProviderSettingsLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Java Headless 各运行模式的生产装配器。
 *
 * <p>所有用户可见诊断都是不含 API Key、端点、Prompt 和 Provider 原始响应的稳定文本。
 * Print 通过 Shutdown Hook 尽力把进程中断传播到 Core；stdio 的取消仍由协议命令驱动。</p>
 *
 * @since 0.1.0
 */
final class DefaultCliModeRunner implements CliModeRunner {

    private final Path repositoryRoot;
    private final InputStream input;
    private final OutputStream output;
    private final PrintWriter printOutput;
    private final PrintWriter errorOutput;
    private final ProviderSettingsLoader settingsLoader = new ProviderSettingsLoader();

    DefaultCliModeRunner(
            Path repositoryRoot,
            InputStream input,
            OutputStream output,
            PrintWriter printOutput,
            PrintWriter errorOutput) {
        this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot 不能为空");
        this.input = Objects.requireNonNull(input, "input 不能为空");
        this.output = Objects.requireNonNull(output, "output 不能为空");
        this.printOutput = Objects.requireNonNull(printOutput, "printOutput 不能为空");
        this.errorOutput = Objects.requireNonNull(errorOutput, "errorOutput 不能为空");
    }

    @Override
    public int runPrint(String prompt, CliOverrides overrides) {
        Objects.requireNonNull(overrides, "overrides 不能为空");
        if (prompt == null
                || prompt.isBlank()
                || prompt.length() > HeadlessRuntimeSession.MAX_PROMPT_CHARS) {
            errorOutput.println("cc-java: --print prompt is empty or too long");
            return CliExitCode.USAGE_OR_CONFIGURATION;
        }

        try {
            PreparedRun prepared = prepare(overrides);
            PrintEventSink events = new PrintEventSink(printOutput);
            try (HeadlessRuntimeSession application =
                         new HeadlessRuntimeSession(
                                 prepared.settings(),
                                 events,
                                 new HeadlessRuntimeOptions(
                                         prepared.workspace(),
                                         prepared.settings().model(),
                                         overrides.timeout(),
                                         overrides.permissionMode(),
                                         java.util.List.of(),
                                         overrides.sessionOpenRequest(),
                                         SessionStorage.defaultRoot(),
                                         overrides.contextPreparation()))) {
                application.open();
                Thread shutdownHook = Thread.ofPlatform()
                        .name("cc-java-print-cancel")
                        .unstarted(application::cancelActive);
                Runtime.getRuntime().addShutdownHook(shutdownHook);
                try {
                    AgentRunResult result = application.run(prompt);
                    events.finish(result);
                    return exitCode(result, errorOutput);
                } finally {
                    removeShutdownHook(shutdownHook);
                }
            }
        } catch (ProviderConfigurationException exception) {
            errorOutput.println(
                    "cc-java: provider configuration invalid (" + exception.code() + ")");
            return CliExitCode.USAGE_OR_CONFIGURATION;
        } catch (WorkspaceConfigurationException exception) {
            errorOutput.println("cc-java: workspace is not an accessible directory");
            return CliExitCode.USAGE_OR_CONFIGURATION;
        } catch (RuntimeException exception) {
            errorOutput.println("cc-java: runtime failed");
            return CliExitCode.RUNTIME_FAILURE;
        }
    }

    @Override
    public int runStdio(CliOverrides overrides) {
        Objects.requireNonNull(overrides, "overrides 不能为空");
        try {
            PreparedRun prepared = prepare(overrides);
            RuntimeStdioCommandHandler handler = new RuntimeStdioCommandHandler(
                    prepared.settings(),
                    prepared.workspace(),
                    overrides.timeout(),
                    overrides.permissionMode(),
                    overrides.sessionOpenRequest(),
                    overrides.contextPreparation());
            StdioProtocolServer.ExitReason reason =
                    new StdioProtocolServer(input, output, handler).run();
            return reason == StdioProtocolServer.ExitReason.INTERNAL_ERROR
                    ? CliExitCode.RUNTIME_FAILURE
                    : CliExitCode.SUCCESS;
        } catch (ProviderConfigurationException exception) {
            errorOutput.println(
                    "cc-java: provider configuration invalid (" + exception.code() + ")");
            return CliExitCode.USAGE_OR_CONFIGURATION;
        } catch (WorkspaceConfigurationException exception) {
            errorOutput.println("cc-java: workspace is not an accessible directory");
            return CliExitCode.USAGE_OR_CONFIGURATION;
        } catch (Exception exception) {
            errorOutput.println("cc-java: headless runtime failed");
            return CliExitCode.RUNTIME_FAILURE;
        }
    }

    private PreparedRun prepare(CliOverrides overrides) {
        Path workspace;
        try {
            if (!Files.isDirectory(overrides.workspace())) {
                throw new WorkspaceConfigurationException();
            }
            workspace = overrides.workspace().toRealPath();
        } catch (IOException exception) {
            throw new WorkspaceConfigurationException();
        }
        OpenAiCompatibleSettings settings = settingsLoader.load(repositoryRoot);
        if (overrides.model().isPresent()) {
            settings = settings.withModel(overrides.model().orElseThrow());
        }
        return new PreparedRun(workspace, settings);
    }

    static int exitCode(AgentRunResult result, PrintWriter errorOutput) {
        if (result.stopReason() == StopReason.COMPLETED) {
            return CliExitCode.SUCCESS;
        }
        if (result.stopReason() == StopReason.USER_CANCELLED) {
            return CliExitCode.USER_CANCELLED;
        }
        if (result.stopReason() == StopReason.TIME_LIMIT_REACHED) {
            errorOutput.println("cc-java: run timed out");
        } else if (result.stopReason() == StopReason.OUTPUT_LIMIT_REACHED) {
            errorOutput.println("cc-java: output limit reached");
        } else {
            errorOutput.println("cc-java: run failed (" + result.stopReason() + ")");
        }
        result.modelFailure().ifPresent(summary ->
                errorOutput.println("cc-java: " + ModelFailureFormatter.format(summary)));
        return CliExitCode.RUNTIME_FAILURE;
    }

    private static void removeShutdownHook(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignoredDuringShutdown) {
            // JVM 已进入关闭序列时，Hook 正在负责传播取消，无需再次移除。
        }
    }

    private record PreparedRun(
            Path workspace,
            OpenAiCompatibleSettings settings) {
    }

    private static final class WorkspaceConfigurationException extends RuntimeException {
    }
}
