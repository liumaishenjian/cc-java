package io.github.liumaishenjian.ccjava.cli.instructions;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 对固定 Local Instructions 候选执行 Git ignore 证明。
 *
 * <p>本策略只接受固定候选，绝不解析 {@code .gitignore} 或拼接 Shell 文本。它在固定工作目录和
 * 最小环境中运行固定 Git 参数；只有命令以零状态完成、输出未溢出且未取消时才放行。启动失败、超时、
 * 取消、输出读取失败及所有不能明确证明安全的状态均 Fail Closed。</p>
 *
 * @since 0.8.0
 */
public final class GitIgnorePolicy {

    private static final String FIXED_CANDIDATE = ".cc-java/AGENTS.local.md";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CANCEL_POLL = Duration.ofMillis(25);
    private static final Duration TERMINATION_WAIT = Duration.ofMillis(250);
    private static final int MAX_OUTPUT_BYTES = 4 * 1024;

    private final Path workspace;
    private final ProcessRunner processRunner;
    private final NanoClock nanoClock;

    /**
     * 固定 Workspace 并使用 JDK 进程适配器。
     *
     * @param workspace 已由调用方固定的真实 Workspace
     */
    public GitIgnorePolicy(Path workspace) {
        this(workspace, builder -> new JdkProcessHandle(builder.start()), System::nanoTime);
    }

    // 该接缝只在同包测试中替换 OS 进程，生产调用仍只能使用下方固定 command()。
    GitIgnorePolicy(Path workspace, ProcessRunner processRunner) {
        this(workspace, processRunner, System::nanoTime);
    }

    GitIgnorePolicy(Path workspace, ProcessRunner processRunner, NanoClock nanoClock) {
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner 不能为空");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock 不能为空");
    }

    /**
     * 验证固定候选是否被 Git 显式忽略。
     *
     * @return 仅在 Git 明确成功时为 {@code true}
     */
    public boolean allowsFixedLocalInstructions() {
        return allowsFixedLocalInstructions(CancellationToken.none());
    }

    /**
     * 验证固定候选是否被 Git 显式忽略，并将调用方取消传播到受控子进程。
     *
     * @param cancellationToken 调用方的取消边界，不能为空
     * @return 仅在 Git 明确成功、输出受限且未取消时为 {@code true}
     */
    public boolean allowsFixedLocalInstructions(CancellationToken cancellationToken) {
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        if (cancellationToken.isCancellationRequested() || !hasSafeFixedCandidate()) {
            return false;
        }
        ProcessHandle process;
        try {
            process = processRunner.start(command());
        } catch (IOException | RuntimeException exception) {
            return false;
        }
        return waitForIgnored(process, cancellationToken, nanoClock);
    }

    private boolean hasSafeFixedCandidate() {
        Path candidate = workspace.resolve(FIXED_CANDIDATE).normalize();
        if (!candidate.startsWith(workspace)) {
            return false;
        }
        try {
            if (!InstructionPathSafety.requireNoLink(candidate).isRegularFile()) {
                return false;
            }
            new WorkspaceGuard(workspace).requireRegularFile(FIXED_CANDIDATE);
            return true;
        } catch (IOException | WorkspaceAccessException exception) {
            return false;
        }
    }

    private ProcessBuilder command() {
        ProcessBuilder builder = new ProcessBuilder(List.of(
                "git", "check-ignore", "--quiet", "--no-index", "--", FIXED_CANDIDATE));
        builder.directory(workspace.toFile());
        Map<String, String> environment = builder.environment();
        String path = environment.get("PATH");
        String systemRoot = environment.get("SystemRoot");
        String pathext = environment.get("PATHEXT");
        environment.clear();
        if (path != null) {
            environment.put("PATH", path);
        }
        if (systemRoot != null) {
            environment.put("SystemRoot", systemRoot);
        }
        if (pathext != null) {
            environment.put("PATHEXT", pathext);
        }
        environment.put("GIT_PAGER", "cat");
        environment.put("GIT_OPTIONAL_LOCKS", "0");
        return builder;
    }

    private static boolean waitForIgnored(ProcessHandle process, CancellationToken token, NanoClock nanoClock) {
        BoundedDrain stdout = new BoundedDrain(process.stdout());
        BoundedDrain stderr = new BoundedDrain(process.stderr());
        Thread stdoutThread = Thread.ofVirtual().start(stdout);
        Thread stderrThread = Thread.ofVirtual().start(stderr);
        boolean successful = false;
        try {
            long deadline = nanoClock.nanoTime() + TIMEOUT.toNanos();
            while (process.isAlive()) {
                if (token.isCancellationRequested() || nanoClock.nanoTime() >= deadline) {
                    return false;
                }
                long remaining = deadline - nanoClock.nanoTime();
                process.await(Math.min(CANCEL_POLL.toNanos(), Math.max(1L, remaining)), TimeUnit.NANOSECONDS);
            }
            successful = !token.isCancellationRequested()
                    && process.exitCode() == 0
                    && joinDrains(stdoutThread, stderrThread, stdout, stderr, deadline, nanoClock)
                    && !stdout.exceeded()
                    && !stderr.exceeded();
            return successful;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException exception) {
            return false;
        } finally {
            if (!successful) {
                terminate(process);
                stdout.close();
                stderr.close();
                joinDrains(stdoutThread, stderrThread, stdout, stderr,
                        nanoClock.nanoTime() + TERMINATION_WAIT.toNanos(), nanoClock);
            }
        }
    }

    private static boolean joinDrains(
            Thread stdoutThread, Thread stderrThread, BoundedDrain stdout, BoundedDrain stderr,
            long deadline, NanoClock nanoClock) {
        try {
            joinUntil(stdoutThread, deadline, nanoClock);
            joinUntil(stderrThread, deadline, nanoClock);
            return !stdoutThread.isAlive() && !stderrThread.isAlive()
                    && !stdout.failed() && !stderr.failed();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void joinUntil(Thread thread, long deadline, NanoClock nanoClock) throws InterruptedException {
        while (thread.isAlive()) {
            long remaining = deadline - nanoClock.nanoTime();
            if (remaining <= 0) {
                return;
            }
            thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
        }
    }

    // 异常路径必须尝试完整升级销毁序列；任一步失败也不能阻止后续清理。
    private static void terminate(ProcessHandle process) {
        try {
            process.destroy();
        } catch (RuntimeException ignored) {
            // 继续尝试强制销毁，避免失败的温和销毁留下子进程。
        }
        try {
            process.destroyForcibly();
        } catch (RuntimeException ignored) {
            // 后续有界等待仍可发现进程未退出，调用方保持 Fail Closed。
        }
        try {
            process.await(TERMINATION_WAIT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // 进程适配器异常同样是不确定状态，不能转为允许。
        }
    }

    @FunctionalInterface
    interface NanoClock {
        long nanoTime();
    }

    @FunctionalInterface
    interface ProcessRunner {
        ProcessHandle start(ProcessBuilder builder) throws IOException;
    }

    interface ProcessHandle {
        InputStream stdout();

        InputStream stderr();

        boolean isAlive();

        boolean await(long timeout, TimeUnit unit) throws InterruptedException;

        int exitCode();

        void destroy();

        void destroyForcibly();
    }

    private record JdkProcessHandle(Process process) implements ProcessHandle {
        @Override
        public InputStream stdout() {
            return process.getInputStream();
        }

        @Override
        public InputStream stderr() {
            return process.getErrorStream();
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return process.waitFor(timeout, unit);
        }

        @Override
        public int exitCode() {
            return process.exitValue();
        }

        @Override
        public void destroy() {
            process.destroy();
        }

        @Override
        public void destroyForcibly() {
            process.destroyForcibly();
        }
    }

    private static final class BoundedDrain implements Runnable {
        private final InputStream input;
        private volatile boolean exceeded;
        private volatile boolean failed;

        private BoundedDrain(InputStream input) {
            this.input = Objects.requireNonNull(input, "input 不能为空");
        }

        @Override
        public void run() {
            byte[] buffer = new byte[1_024];
            int total = 0;
            try (input) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_OUTPUT_BYTES) {
                        exceeded = true;
                        return;
                    }
                }
            } catch (IOException exception) {
                failed = true;
            }
        }

        private void close() {
            try {
                input.close();
            } catch (IOException exception) {
                failed = true;
            }
        }

        private boolean exceeded() {
            return exceeded;
        }

        private boolean failed() {
            return failed;
        }
    }
}
