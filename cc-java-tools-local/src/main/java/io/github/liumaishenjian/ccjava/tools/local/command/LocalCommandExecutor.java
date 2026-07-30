package io.github.liumaishenjian.ccjava.tools.local.command;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ToolOutputSink;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.ToolOutputStream;
import io.github.liumaishenjian.ccjava.tools.local.workspace.LocalToolLimits;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 在固定 Workspace、Shell、环境和资源预算下执行前台命令。
 *
 * <p>子进程 stdin 在启动后立即关闭，因此 S04 不支持交互式 TTY。stdout/stderr
 * 始终被并发消费，避免管道回压死锁；模型可见结果和 Surface 事件分别有界。
 * timeout 与取消都会进入同一个进程树清理路径。</p>
 *
 * @since 0.4.0
 */
public final class LocalCommandExecutor {

    private final Path workspace;
    private final CommandShell shell;
    private final ProcessTreeTerminator terminator;

    /**
     * 为固定 Workspace 创建平台命令执行器。
     *
     * @param workspace 已解析的真实 Workspace
     */
    public LocalCommandExecutor(Path workspace) {
        this(workspace, CommandShell.current(), new ProcessTreeTerminator());
    }

    LocalCommandExecutor(
            Path workspace,
            CommandShell shell,
            ProcessTreeTerminator terminator) {
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
        this.shell = Objects.requireNonNull(shell, "shell 不能为空");
        this.terminator = Objects.requireNonNull(terminator, "terminator 不能为空");
    }

    /**
     * 执行已通过参数校验与审批的完整命令。
     *
     * @param command 审批时展示的完整命令正文
     * @param timeout 正数且不超过固定上限的期限
     * @param cancellation 当前 Run 取消信号
     * @param outputSink 有界输出事件出口
     * @return 退出码、终止原因和有界输出
     * @throws IOException Shell 无法启动或输出无法消费时
     */
    public CommandExecutionResult execute(
            String command,
            Duration timeout,
            CancellationToken cancellation,
            ToolOutputSink outputSink) throws IOException {
        Objects.requireNonNull(command, "command 不能为空");
        Objects.requireNonNull(timeout, "timeout 不能为空");
        Objects.requireNonNull(cancellation, "cancellation 不能为空");
        Objects.requireNonNull(outputSink, "outputSink 不能为空");

        ProcessBuilder builder = new ProcessBuilder(shell.processArguments(command));
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(false);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.putAll(CommandEnvironment.minimal());

        Process process = builder.start();
        process.getOutputStream().close();
        BoundedOutput output = new BoundedOutput(LocalToolLimits.MAX_COMMAND_OUTPUT_CHARACTERS);
        Thread stdout = pump(
                process.getInputStream(), shell.outputCharset(), ToolOutputStream.STDOUT,
                output, outputSink);
        Thread stderr = pump(
                process.getErrorStream(), shell.outputCharset(), ToolOutputStream.STDERR,
                output, outputSink);

        long deadline = System.nanoTime() + timeout.toNanos();
        boolean timedOut = false;
        boolean cancelled = false;
        try {
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (cancellation.isCancellationRequested()) {
                    cancelled = true;
                    terminator.terminate(process);
                    break;
                }
                if (System.nanoTime() >= deadline) {
                    timedOut = true;
                    terminator.terminate(process);
                    break;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancelled = true;
            terminator.terminate(process);
        }
        join(stdout);
        join(stderr);
        int exitCode = process.isAlive() ? -1 : process.exitValue();
        return output.result(shell.id(), exitCode, timedOut, cancelled);
    }

    private static Thread pump(
            InputStream input,
            java.nio.charset.Charset charset,
            ToolOutputStream stream,
            BoundedOutput output,
            ToolOutputSink sink) {
        return Thread.ofVirtual().name("cc-java-command-" + stream.name().toLowerCase())
                .start(() -> {
                    try (InputStreamReader reader = new InputStreamReader(input, charset)) {
                        char[] buffer = new char[2_048];
                        int read;
                        while ((read = reader.read(buffer)) >= 0) {
                            if (read > 0) {
                                String visible = output.append(stream, new String(buffer, 0, read));
                                publishChunks(stream, visible, sink);
                            }
                        }
                    } catch (IOException ignored) {
                        // 进程树终止会关闭管道；退出原因由主等待循环决定。
                    }
                });
    }

    private static void publishChunks(
            ToolOutputStream stream,
            String value,
            ToolOutputSink sink) {
        String remaining = value;
        while (!remaining.isEmpty()) {
            int points = Math.min(
                    LifecycleEvent.ToolOutput.MAX_CHUNK_CHARACTERS,
                    remaining.codePointCount(0, remaining.length()));
            int end = remaining.offsetByCodePoints(0, points);
            try {
                sink.publish(stream, remaining.substring(0, end));
            } catch (RuntimeException ignored) {
                // 输出观察者不是执行控制面。
            }
            remaining = remaining.substring(end);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(2_000);
            if (thread.isAlive()) {
                thread.interrupt();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class BoundedOutput {
        private final int limit;
        private final StringBuilder stdout = new StringBuilder();
        private final StringBuilder stderr = new StringBuilder();
        private long originalCharacters;
        private int retainedCharacters;

        private BoundedOutput(int limit) {
            this.limit = limit;
        }

        private synchronized String append(ToolOutputStream stream, String value) {
            int characters = value.codePointCount(0, value.length());
            originalCharacters += characters;
            int accepted = Math.min(characters, Math.max(0, limit - retainedCharacters));
            if (accepted == 0) {
                return "";
            }
            String visible = accepted == characters
                    ? value : value.substring(0, value.offsetByCodePoints(0, accepted));
            (stream == ToolOutputStream.STDOUT ? stdout : stderr).append(visible);
            retainedCharacters += accepted;
            return visible;
        }

        private synchronized CommandExecutionResult result(
                String shell,
                int exitCode,
                boolean timedOut,
                boolean cancelled) {
            return new CommandExecutionResult(
                    shell,
                    exitCode,
                    timedOut,
                    cancelled,
                    stdout.toString(),
                    stderr.toString(),
                    originalCharacters > retainedCharacters,
                    originalCharacters);
        }
    }
}
