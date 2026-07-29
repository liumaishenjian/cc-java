package io.github.liumaishenjian.ccjava.cli;

import picocli.CommandLine;

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
        CommandLine commandLine = new CommandLine(new CcJavaCommand(runner));
        commandLine.setOut(out);
        commandLine.setErr(err);
        return commandLine.execute(args);
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
