package io.github.liumaishenjian.ccjava.cli.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.hook.HookBinding;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.hook.HookDisposition;
import io.github.liumaishenjian.ccjava.domain.hook.HookEventKind;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionStatus;
import io.github.liumaishenjian.ccjava.domain.hook.HookFailurePolicy;
import io.github.liumaishenjian.ccjava.domain.hook.HookInvocation;
import io.github.liumaishenjian.ccjava.domain.hook.HookMatcher;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证 Command Hook 的 argv、JSON 协议、输出边界和进程终止语义。
 *
 * @since 0.1.0
 */
class CommandHookHandlerTest {

    private static final HookInvocation INVOCATION = new HookInvocation(
            HookEventKind.PRE_TOOL,
            new SessionId("session-1"),
            java.util.Optional.of(new RunId("run-1")),
            "read_file",
            new JsonObject(Map.of("callId", "call-1", "toolName", "read_file")));

    @Test
    void sendsOnlyBoundedStructuredInputAndParsesDecision(@TempDir Path workspace)
            throws java.io.IOException {
        AtomicReference<String> commandSeen = new AtomicReference<>();
        AtomicReference<Path> workspaceSeen = new AtomicReference<>();
        AtomicReference<String> inputSeen = new AtomicReference<>();
        CommandHookHandler handler = handler(
                workspace,
                (command, workingDirectory) -> {
                    commandSeen.set(String.join("|", command));
                    workspaceSeen.set(workingDirectory);
                    ScriptedProcess process = new ScriptedProcess(
                            "{\"disposition\":\"BLOCK\",\"reason\":\"guarded\",\"additionalContext\":\"safe\"}",
                            "",
                            0,
                            true);
                    process.capture(inputSeen);
                    return process;
                });

        var result = handler.execute(INVOCATION, CancellationToken.none());

        assertThat(result.disposition()).isEqualTo(HookDisposition.BLOCK);
        assertThat(result.status()).isEqualTo(HookExecutionStatus.COMPLETED);
        assertThat(result.reason()).contains("guarded");
        assertThat(commandSeen).hasValue(workspace.resolve("hook.exe").toString());
        assertThat(workspaceSeen).hasValue(workspace.toRealPath());
        assertThat(inputSeen).hasValueSatisfying(input -> {
            assertThat(input).contains("\"event\":\"PRE_TOOL\"");
            assertThat(input).contains("\"sessionId\":\"session-1\"");
            assertThat(input).contains("\"callId\":\"call-1\"");
        });
    }

    @Test
    void malformedOutputBecomesFailClosedBlockAtCoordinator(@TempDir Path workspace) {
        CommandHookHandler handler = handler(
                workspace,
                (command, workingDirectory) -> new ScriptedProcess("{}", "", 0, true));
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            HookCoordinator coordinator = new HookCoordinator(
                    List.of(new HookBinding(
                            "command",
                            HookMatcher.event(HookEventKind.PRE_TOOL),
                            handler,
                            HookFailurePolicy.FAIL_CLOSED,
                            true,
                            0)),
                    executor,
                    Duration.ofSeconds(1));

            var result = coordinator.evaluate(INVOCATION, CancellationToken.none());

            assertThat(result.disposition()).isEqualTo(HookDisposition.BLOCK);
            assertThat(result.executions().getFirst().status())
                    .isEqualTo(HookExecutionStatus.INVALID_OUTPUT);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void timeoutDestroysProcessAndReturnsTimedOut(@TempDir Path workspace) {
        AtomicReference<ScriptedProcess> processSeen = new AtomicReference<>();
        CommandHookHandler handler = handler(
                workspace,
                (command, workingDirectory) -> {
                    ScriptedProcess process = new ScriptedProcess("", "", 0, false);
                    processSeen.set(process);
                    return process;
                },
                Duration.ofMillis(5),
                1_024);

        var result = handler.execute(INVOCATION, CancellationToken.none());

        assertThat(result.status()).isEqualTo(HookExecutionStatus.TIMED_OUT);
        assertThat(processSeen).hasValueSatisfying(process -> assertThat(process.destroyed).isTrue());
    }

    @Test
    void cancellationBeforeSpawnDoesNotStartProcess(@TempDir Path workspace) {
        AtomicReference<Boolean> started = new AtomicReference<>(false);
        CommandHookHandler handler = handler(
                workspace,
                (command, workingDirectory) -> {
                    started.set(true);
                    return new ScriptedProcess("{}", "", 0, true);
                });
        CancellationSource source = new CancellationSource();
        source.cancel();

        var result = handler.execute(INVOCATION, source.token());

        assertThat(result.status()).isEqualTo(HookExecutionStatus.CANCELLED);
        assertThat(started).hasValue(false);
    }

    @Test
    void outputLimitIsReportedWithoutReturningRawOutput(@TempDir Path workspace) {
        CommandHookHandler handler = handler(
                workspace,
                (command, workingDirectory) -> new ScriptedProcess("x".repeat(300), "", 0, true),
                Duration.ofSeconds(1),
                256);

        var result = handler.execute(INVOCATION, CancellationToken.none());

        assertThat(result.status()).isEqualTo(HookExecutionStatus.INVALID_OUTPUT);
        assertThat(result.reason()).contains("Hook 输出超过上限");
        assertThat(result.additionalContext()).isEmpty();
    }

    @Test
    void rejectsTrailingJsonTokens(@TempDir Path workspace) {
        CommandHookHandler handler = handler(
                workspace,
                (command, workingDirectory) -> new ScriptedProcess(
                        "{\"disposition\":\"CONTINUE\"} {\"extra\":true}", "", 0, true));

        var result = handler.execute(INVOCATION, CancellationToken.none());

        assertThat(result.status()).isEqualTo(HookExecutionStatus.INVALID_OUTPUT);
    }

    @Test
    void blockedStdinWriteIsCoveredByDeadlineAndUnblockedDuringCleanup(@TempDir Path workspace) {
        AtomicReference<BlockingInputProcess> processSeen = new AtomicReference<>();
        CommandHookHandler handler = handler(
                workspace,
                (command, workingDirectory) -> {
                    BlockingInputProcess process = new BlockingInputProcess();
                    processSeen.set(process);
                    return process;
                },
                Duration.ofMillis(20),
                1_024);

        var result = handler.execute(INVOCATION, CancellationToken.none());

        assertThat(result.status()).isEqualTo(HookExecutionStatus.TIMED_OUT);
        assertThat(processSeen).hasValueSatisfying(process -> {
            assertThat(process.destroyed).isTrue();
            assertThat(process.stdin.closed).isTrue();
        });
    }

    private static CommandHookHandler handler(
            Path workspace,
            CommandHookHandler.ProcessLauncher launcher) {
        return handler(workspace, launcher, CommandHookHandler.DEFAULT_TIMEOUT,
                CommandHookHandler.DEFAULT_MAX_OUTPUT_BYTES);
    }

    private static CommandHookHandler handler(
            Path workspace,
            CommandHookHandler.ProcessLauncher launcher,
            Duration timeout,
            int maxOutputBytes) {
        return new CommandHookHandler(
                "command",
                List.of(workspace.resolve("hook.exe").toString()),
                workspace,
                timeout,
                maxOutputBytes,
                launcher);
    }

    private static final class ScriptedProcess implements CommandHookHandler.CommandProcess {
        private final ByteArrayInputStream stdout;
        private final ByteArrayInputStream stderr;
        private final CapturedInput stdin = new CapturedInput();
        private final int exitCode;
        private final boolean completes;
        private volatile boolean destroyed;
        private AtomicReference<String> capture;

        private ScriptedProcess(String stdout, String stderr, int exitCode, boolean completes) {
            this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
            this.stderr = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
            this.exitCode = exitCode;
            this.completes = completes;
        }

        private void capture(AtomicReference<String> target) {
            capture = target;
        }

        @Override
        public OutputStream stdin() {
            return stdin;
        }

        @Override
        public InputStream stdout() {
            return stdout;
        }

        @Override
        public InputStream stderr() {
            return stderr;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (completes) {
                return true;
            }
            Thread.sleep(Math.min(2, Math.max(1, unit.toMillis(timeout))));
            return false;
        }

        @Override
        public int exitCode() {
            return exitCode;
        }

        @Override
        public boolean isAlive() {
            return !completes && !destroyed;
        }

        @Override
        public void destroyTree() {
            destroyed = true;
        }

        private final class CapturedInput extends ByteArrayOutputStream {
            @Override
            public void close() {
                if (capture != null) {
                    capture.set(toString(StandardCharsets.UTF_8));
                }
            }
        }
    }

    private static final class BlockingInputProcess implements CommandHookHandler.CommandProcess {
        private final BlockingOutputStream stdin = new BlockingOutputStream();
        private volatile boolean destroyed;

        @Override public OutputStream stdin() { return stdin; }
        @Override public InputStream stdout() { return InputStream.nullInputStream(); }
        @Override public InputStream stderr() { return InputStream.nullInputStream(); }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            Thread.sleep(Math.min(2, Math.max(1, unit.toMillis(timeout))));
            return false;
        }
        @Override public int exitCode() { return 0; }
        @Override public boolean isAlive() { return !destroyed; }
        @Override public void destroyTree() { destroyed = true; }
    }

    private static final class BlockingOutputStream extends OutputStream {
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean closed;

        @Override
        public void write(int value) throws java.io.IOException {
            try {
                released.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("interrupted", interrupted);
            }
            if (closed) {
                throw new java.io.IOException("closed");
            }
        }

        @Override
        public void close() {
            closed = true;
            released.countDown();
        }
    }
}
