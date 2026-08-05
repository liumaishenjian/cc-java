package io.github.liumaishenjian.ccjava.cli.instructions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitIgnorePolicyTest {

    @TempDir
    Path temporary;

    @Test
    void permitsOnlyExplicitlyIgnoredFixedCandidate() throws Exception {
        assumeGitAvailable();
        Path workspace = repository("ignored");
        Files.writeString(workspace.resolve(".gitignore"), ".cc-java/AGENTS.local.md\n");
        createCandidate(workspace);

        assertThat(new GitIgnorePolicy(workspace).allowsFixedLocalInstructions()).isTrue();
    }

    @Test
    void rejectsNonIgnoredNonRepositoryAndAbsentCandidate() throws Exception {
        assumeGitAvailable();
        Path tracked = repository("tracked");
        createCandidate(tracked);
        assertThat(new GitIgnorePolicy(tracked).allowsFixedLocalInstructions()).isFalse();

        Path nonRepository = Files.createDirectory(temporary.resolve("non-repository"));
        createCandidate(nonRepository);
        assertThat(new GitIgnorePolicy(nonRepository).allowsFixedLocalInstructions()).isFalse();

        assertThat(new GitIgnorePolicy(repository("absent")).allowsFixedLocalInstructions()).isFalse();
    }

    @Test
    void rejectsUnsafeCandidateAndDoesNotStartProcess() throws Exception {
        Path workspace = workspace("unsafe");
        AtomicInteger starts = new AtomicInteger();

        assertThat(policy(workspace, builder -> {
            starts.incrementAndGet();
            return new FakeProcess(false, 0);
        }).allowsFixedLocalInstructions()).isFalse();
        assertThat(starts).hasValue(0);
    }

    @Test
    void configuresFixedArgvWorkingDirectoryAndMinimalEnvironment() throws Exception {
        Path workspace = workspace("configuration");
        createCandidate(workspace);
        FakeProcess process = new FakeProcess(false, 0);

        assertThat(policy(workspace, builder -> {
            assertThat(builder.command()).containsExactly(
                    "git", "check-ignore", "--quiet", "--no-index", "--", ".cc-java/AGENTS.local.md");
            assertThat(builder.directory().toPath()).isEqualTo(workspace);
            Map<String, String> environment = builder.environment();
            assertThat(environment).containsEntry("GIT_PAGER", "cat")
                    .containsEntry("GIT_OPTIONAL_LOCKS", "0");
            assertThat(environment.keySet()).allMatch(key -> key.equals("PATH")
                    || key.equals("SystemRoot") || key.equals("PATHEXT")
                    || key.equals("GIT_PAGER") || key.equals("GIT_OPTIONAL_LOCKS"));
            return process;
        }).allowsFixedLocalInstructions()).isTrue();
    }

    @Test
    void rejectsStartFailureAndNonzeroExit() throws Exception {
        Path workspace = workspace("start-and-exit");
        createCandidate(workspace);

        assertThat(policy(workspace, builder -> {
            throw new IOException("unavailable");
        }).allowsFixedLocalInstructions()).isFalse();
        assertThat(policy(workspace, builder -> new FakeProcess(false, 1)).allowsFixedLocalInstructions()).isFalse();
    }

    @Test
    void rejectsTimeoutAndCleansUpDrains() throws Exception {
        Path workspace = workspace("timeout");
        createCandidate(workspace);
        AdvancingClock clock = new AdvancingClock();
        FakeProcess process = new FakeProcess(true, 0);

        assertThat(policy(workspace, builder -> process, clock).allowsFixedLocalInstructions()).isFalse();
        assertThat(process.destroyCalls).isGreaterThanOrEqualTo(1);
        assertThat(process.destroyForciblyCalls).isGreaterThanOrEqualTo(1);
        assertThat(process.stdout.closed).isTrue();
        assertThat(process.stderr.closed).isTrue();
    }

    @Test
    void rejectsCancellationAndDestroysProcess() throws Exception {
        Path workspace = workspace("cancel");
        createCandidate(workspace);
        CancellationSource source = new CancellationSource();
        FakeProcess process = new FakeProcess(true, 0);
        process.afterAwait = source::cancel;

        assertThat(policy(workspace, builder -> process).allowsFixedLocalInstructions(source.token())).isFalse();
        assertThat(process.destroyCalls).isGreaterThanOrEqualTo(1);
        assertThat(process.destroyForciblyCalls).isGreaterThanOrEqualTo(1);
    }

    @Test
    void rejectsStdoutAndStderrOverflow() throws Exception {
        Path workspace = workspace("overflow");
        createCandidate(workspace);

        FakeProcess stdoutOverflow = new FakeProcess(false, 0);
        stdoutOverflow.stdout = new TrackingInputStream(new byte[4 * 1024 + 1]);
        assertThat(policy(workspace, builder -> stdoutOverflow).allowsFixedLocalInstructions()).isFalse();

        FakeProcess stderrOverflow = new FakeProcess(false, 0);
        stderrOverflow.stderr = new TrackingInputStream(new byte[4 * 1024 + 1]);
        assertThat(policy(workspace, builder -> stderrOverflow).allowsFixedLocalInstructions()).isFalse();
    }

    @Test
    void rejectsDestroyFailuresAndProcessThatRemainsAlive() throws Exception {
        Path workspace = workspace("destroy-failure");
        createCandidate(workspace);
        AdvancingClock clock = new AdvancingClock();
        FakeProcess process = new FakeProcess(true, 0);
        process.destroyFailure = true;
        process.forceDestroyFailure = true;

        assertThat(policy(workspace, builder -> process, clock).allowsFixedLocalInstructions()).isFalse();
        assertThat(process.destroyCalls).isGreaterThanOrEqualTo(1);
        assertThat(process.destroyForciblyCalls).isGreaterThanOrEqualTo(1);
        assertThat(process.awaitCalls).isGreaterThanOrEqualTo(2);
    }

    private GitIgnorePolicy policy(Path workspace, GitIgnorePolicy.ProcessRunner runner) {
        return new GitIgnorePolicy(workspace, runner);
    }

    private GitIgnorePolicy policy(Path workspace, GitIgnorePolicy.ProcessRunner runner, GitIgnorePolicy.NanoClock clock) {
        return new GitIgnorePolicy(workspace, runner, clock);
    }

    private Path workspace(String name) throws IOException {
        return Files.createDirectory(temporary.resolve(name));
    }

    private void createCandidate(Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve(".cc-java"));
        Files.writeString(workspace.resolve(".cc-java/AGENTS.local.md"), "local instructions");
    }

    private Path repository(String name) throws Exception {
        Path workspace = workspace(name);
        Process process = new ProcessBuilder("git", "init", "--quiet", workspace.toString()).start();
        assertThat(process.waitFor()).isZero();
        return workspace;
    }

    private static void assumeGitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").start();
            Assumptions.assumeTrue(process.waitFor() == 0, "Git 不可用");
        } catch (Exception exception) {
            Assumptions.abort("Git 不可用");
        }
    }

    private static final class AdvancingClock implements GitIgnorePolicy.NanoClock {
        private long current;

        @Override
        public long nanoTime() {
            long value = current;
            current += TimeUnit.SECONDS.toNanos(3);
            return value;
        }
    }

    private static final class FakeProcess implements GitIgnorePolicy.ProcessHandle {
        private boolean alive;
        private final int exitCode;
        private TrackingInputStream stdout = new TrackingInputStream(new byte[0]);
        private TrackingInputStream stderr = new TrackingInputStream(new byte[0]);
        private boolean destroyFailure;
        private boolean forceDestroyFailure;
        private Runnable afterAwait = () -> { };
        private int destroyCalls;
        private int destroyForciblyCalls;
        private int awaitCalls;

        private FakeProcess(boolean alive, int exitCode) {
            this.alive = alive;
            this.exitCode = exitCode;
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
        public boolean isAlive() {
            return alive;
        }

        @Override
        public boolean await(long timeout, TimeUnit unit) {
            awaitCalls++;
            afterAwait.run();
            return false;
        }

        @Override
        public int exitCode() {
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyCalls++;
            if (destroyFailure) {
                throw new IllegalStateException("destroy failed");
            }
        }

        @Override
        public void destroyForcibly() {
            destroyForciblyCalls++;
            if (forceDestroyFailure) {
                throw new IllegalStateException("force destroy failed");
            }
            alive = false;
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] content) {
            super(content);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
