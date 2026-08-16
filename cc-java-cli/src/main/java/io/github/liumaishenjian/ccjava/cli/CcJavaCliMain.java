package io.github.liumaishenjian.ccjava.cli;

import picocli.CommandLine;
import io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * cc-java Java Headless Composition Root。
 *
 * <p>Picocli 只解析 {@code --print}/{@code --stdio} 和标准帮助参数。Agent Runtime、
 * Provider 和 stdio Handler 的真实装配由 {@link DefaultCliModeRunner} 完成；
 * stdout 在 stdio 模式下仍严格专用于协议事件。</p>
 *
 * @since 0.1.0
 */
public final class CcJavaCliMain {

    private static final String REPOSITORY_ROOT_ENV = "CC_JAVA_REPOSITORY_ROOT";

    private CcJavaCliMain() {
    }

    /**
     * 启动 Java Headless 并把稳定退出码交还操作系统。
     *
     * @param args Picocli 参数
     */
    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(
                System.out,
                true,
                StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(
                System.err,
                true,
                StandardCharsets.UTF_8);
        CliModeRunner runner = new DefaultCliModeRunner(
                repositoryRoot(System.getenv()),
                System.in,
                System.out,
                out,
                err);
        System.exit(execute(args, runner, out, err));
    }

    static int execute(
            String[] args,
            CliModeRunner runner,
            PrintWriter out,
            PrintWriter err) {
        if (args.length > 0 && java.util.Set.of("providers", "auth", "models").contains(args[0])) {
            Path userHome = Path.of(java.util.Objects.requireNonNull(
                    System.getProperty("user.home"), "user.home 不能为空"));
            try (var resources = io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthRuntimeResources.open(
                    userHome, repositoryRoot(System.getenv()), System.getenv())) {
                return executeProviderControl(args, resources.service(), System.in, out, err);
            }
        }
        CommandLine commandLine = new CommandLine(new CcJavaCommand(runner));
        // Headless stdout/stderr 是可脚本化协议面；不能因父终端颜色环境变量改变字节内容。
        commandLine.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        commandLine.setOut(out);
        commandLine.setErr(err);
        return commandLine.execute(args);
    }

    static int executeProviderControl(
            String[] args,
            ProviderAuthApplicationService service,
            java.io.InputStream input,
            PrintWriter out,
            PrintWriter err) {
        CommandLine root = new CommandLine(new ProviderControlRoot());
        root.addSubcommand(ProviderControlCommands.providers(service, out, err));
        root.addSubcommand(ProviderControlCommands.auth(service, input, out, err));
        root.addSubcommand(ProviderControlCommands.models(service, out, err));
        root.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        root.setOut(out);
        root.setErr(err);
        return root.execute(args);
    }

    @picocli.CommandLine.Command(name = "cc-java", mixinStandardHelpOptions = true)
    private static final class ProviderControlRoot implements java.util.concurrent.Callable<Integer> {
        @Override public Integer call() { return 2; }
    }
    static Path repositoryRoot(Map<String, String> environment) {
        String configured = environment.get(REPOSITORY_ROOT_ENV);
        return (configured == null || configured.isBlank()
                ? Path.of("")
                : Path.of(configured))
                .toAbsolutePath()
                .normalize();
    }
}
