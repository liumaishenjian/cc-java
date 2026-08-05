package io.github.liumaishenjian.ccjava.cli.settings;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SettingsLocalGitIgnorePolicyTest {

    @Test
    void cancellationDuringCheckReturnsCancelledAndTerminatesProcess() {
        CancellationSource cancellation = new CancellationSource();
        FakeProcess process = FakeProcess.running(() -> cancellation.cancel());
        SettingsLocalGitIgnorePolicy policy = policy(process);

        SettingsLocalGitIgnorePolicy.Verification result = policy.verifyFixedLocalSettings(cancellation.token());

        assertThat(result).isEqualTo(SettingsLocalGitIgnorePolicy.Verification.CANCELLED);
        assertThat(process.destroyed).isTrue();
        assertThat(process.destroyedForcibly).isTrue();
    }

    @Test
    void nonIgnoredExitIsDistinctFromExecutionFailure() {
        assertThat(policy(FakeProcess.completed(1, InputStream.nullInputStream(), InputStream.nullInputStream()))
                .verifyFixedLocalSettings(CancellationToken.none()))
                .isEqualTo(SettingsLocalGitIgnorePolicy.Verification.NOT_IGNORED);
        assertThat(policy(FakeProcess.completed(2, InputStream.nullInputStream(), InputStream.nullInputStream()))
                .verifyFixedLocalSettings(CancellationToken.none()))
                .isEqualTo(SettingsLocalGitIgnorePolicy.Verification.FAILED);
    }

    @Test
    void oversizedOrUnreadableOutputCannotPermitZeroExit() {
        assertThat(policy(FakeProcess.completed(0, new ByteArrayInputStream(new byte[4 * 1024 + 1]),
                InputStream.nullInputStream())).verifyFixedLocalSettings(CancellationToken.none()))
                .isEqualTo(SettingsLocalGitIgnorePolicy.Verification.FAILED);
        assertThat(policy(FakeProcess.completed(0, new FailingInputStream(), InputStream.nullInputStream()))
                .verifyFixedLocalSettings(CancellationToken.none()))
                .isEqualTo(SettingsLocalGitIgnorePolicy.Verification.FAILED);
    }

    private static SettingsLocalGitIgnorePolicy policy(FakeProcess process) {
        return new SettingsLocalGitIgnorePolicy(Path.of("."), builder -> process, System::nanoTime);
    }

    private static final class FakeProcess implements SettingsLocalGitIgnorePolicy.ProcessHandle {
        private final int exitCode;
        private final InputStream stdout;
        private final InputStream stderr;
        private final Runnable awaitAction;
        private boolean alive;
        private boolean destroyed;
        private boolean destroyedForcibly;

        private FakeProcess(int exitCode, InputStream stdout, InputStream stderr, boolean alive, Runnable awaitAction) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.alive = alive;
            this.awaitAction = awaitAction;
        }

        static FakeProcess completed(int exitCode, InputStream stdout, InputStream stderr) {
            return new FakeProcess(exitCode, stdout, stderr, false, () -> { });
        }

        static FakeProcess running(Runnable awaitAction) {
            return new FakeProcess(0, InputStream.nullInputStream(), InputStream.nullInputStream(), true, awaitAction);
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
            awaitAction.run();
            return false;
        }

        @Override
        public int exitCode() {
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public void destroyForcibly() {
            destroyedForcibly = true;
            alive = false;
        }
    }

    private static final class FailingInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("expected test failure");
        }
    }
}
