package io.github.liumaishenjian.ccjava.cli.settings;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 对固定 {@code settings.local.json} 候选执行 Git ignore 的 fail-closed 验证。
 *
 * <p>本策略不解析 {@code .gitignore}、不接受任意路径，也不通过 Shell 启动 Git。只有固定命令
 * 在固定 Workspace、最小环境和受限输出下以零状态完成时才返回 {@link Verification#IGNORED}；
 * 取消、超时、启动或读取失败均为拒绝状态。</p>
 *
 * @since 0.8.0
 */
public final class SettingsLocalGitIgnorePolicy {
    private static final String FIXED_CANDIDATE = ".cc-java/settings.local.json";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POLL = Duration.ofMillis(25);
    private static final Duration TERMINATION_WAIT = Duration.ofMillis(250);
    private static final int MAX_OUTPUT_BYTES = 4 * 1024;

    private final Path workspace;
    private final ProcessRunner processRunner;
    private final NanoClock nanoClock;

    /**
     * 创建固定 Workspace 的 Git ignore 验证器。
     *
     * @param workspace 已固定真实 Workspace
     */
    public SettingsLocalGitIgnorePolicy(Path workspace) {
        this(workspace, builder -> new JdkProcessHandle(builder.start()), System::nanoTime);
    }

    SettingsLocalGitIgnorePolicy(Path workspace, ProcessRunner processRunner) {
        this(workspace, processRunner, System::nanoTime);
    }

    SettingsLocalGitIgnorePolicy(Path workspace, ProcessRunner processRunner, NanoClock nanoClock) {
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner 不能为空");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock 不能为空");
    }

    /**
     * 验证固定 local Settings 是否被 Git 显式忽略。
     *
     * @param cancellationToken 调用方取消边界
     * @return 明确忽略、未忽略、已取消或不可证明的失败状态
     */
    public Verification verifyFixedLocalSettings(CancellationToken cancellationToken) {
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        if (cancellationToken.isCancellationRequested()) {
            return Verification.CANCELLED;
        }
        ProcessHandle process;
        try {
            process = processRunner.start(command());
        } catch (IOException | RuntimeException exception) {
            return Verification.FAILED;
        }
        return waitForResult(process, cancellationToken, nanoClock);
    }

    private ProcessBuilder command() {
        ProcessBuilder builder = new ProcessBuilder(List.of(
                "git", "check-ignore", "--quiet", "--no-index", "--", FIXED_CANDIDATE));
        builder.directory(workspace.toFile());
        minimalEnvironment(builder.environment());
        return builder;
    }

    private static Verification waitForResult(ProcessHandle process, CancellationToken token, NanoClock clock) {
        BoundedDrain stdout = new BoundedDrain(process.stdout());
        BoundedDrain stderr = new BoundedDrain(process.stderr());
        Thread stdoutThread = Thread.ofVirtual().start(stdout);
        Thread stderrThread = Thread.ofVirtual().start(stderr);
        boolean completed = false;
        try {
            long deadline = clock.nanoTime() + TIMEOUT.toNanos();
            while (process.isAlive()) {
                if (token.isCancellationRequested()) {
                    return Verification.CANCELLED;
                }
                if (clock.nanoTime() >= deadline) {
                    return Verification.FAILED;
                }
                long remaining = deadline - clock.nanoTime();
                process.await(Math.min(POLL.toNanos(), Math.max(1L, remaining)), TimeUnit.NANOSECONDS);
            }
            if (token.isCancellationRequested()) {
                return Verification.CANCELLED;
            }
            if (!joinDrains(stdoutThread, stderrThread, stdout, stderr, deadline, clock)
                    || stdout.exceeded() || stderr.exceeded()) {
                return Verification.FAILED;
            }
            completed = true;
            return process.exitCode() == 0 ? Verification.IGNORED
                    : process.exitCode() == 1 ? Verification.NOT_IGNORED : Verification.FAILED;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Verification.FAILED;
        } catch (RuntimeException exception) {
            return Verification.FAILED;
        } finally {
            if (!completed) {
                terminate(process);
                stdout.close();
                stderr.close();
                joinDrains(stdoutThread, stderrThread, stdout, stderr,
                        clock.nanoTime() + TERMINATION_WAIT.toNanos(), clock);
            }
        }
    }

    private static boolean joinDrains(Thread stdoutThread, Thread stderrThread, BoundedDrain stdout, BoundedDrain stderr,
                                      long deadline, NanoClock clock) {
        try {
            joinUntil(stdoutThread, deadline, clock);
            joinUntil(stderrThread, deadline, clock);
            return !stdoutThread.isAlive() && !stderrThread.isAlive() && !stdout.failed() && !stderr.failed();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void joinUntil(Thread thread, long deadline, NanoClock clock) throws InterruptedException {
        while (thread.isAlive()) {
            long remaining = deadline - clock.nanoTime();
            if (remaining <= 0) {
                return;
            }
            thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
        }
    }

    private static void terminate(ProcessHandle process) {
        try {
            process.destroy();
        } catch (RuntimeException ignored) {
            // 无法温和终止时仍须继续强制终止。
        }
        try {
            process.destroyForcibly();
        } catch (RuntimeException ignored) {
            // 终止失败保持 fail closed，并继续执行有界等待。
        }
        try {
            process.await(TERMINATION_WAIT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // 不确定的进程状态不能转化为允许。
        }
    }

    private static void minimalEnvironment(Map<String, String> environment) {
        String path = environment.get("PATH");
        String systemRoot = environment.get("SystemRoot");
        String pathext = environment.get("PATHEXT");
        environment.clear();
        if (path != null) environment.put("PATH", path);
        if (systemRoot != null) environment.put("SystemRoot", systemRoot);
        if (pathext != null) environment.put("PATHEXT", pathext);
        environment.put("GIT_PAGER", "cat");
        environment.put("GIT_OPTIONAL_LOCKS", "0");
    }

    enum Verification {
        IGNORED,
        NOT_IGNORED,
        CANCELLED,
        FAILED
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
