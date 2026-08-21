package io.github.liumaishenjian.ccjava.tools.local.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceStateDigestTest {
    @TempDir Path temporary;

    @Test
    void ignoresIgnoredTreesButTracksWorkingIndexAndUntrackedState() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("repository"));
        git(repository, "init");
        git(repository, "config", "user.name", "Fixture");
        git(repository, "config", "user.email", "fixture@example.invalid");
        Files.writeString(repository.resolve(".gitignore"), "target/\nnode_modules/\n");
        Files.writeString(repository.resolve("tracked.txt"), "base\n");
        git(repository, "add", ".gitignore", "tracked.txt");
        git(repository, "commit", "-m", "base");
        Files.createDirectories(repository.resolve("target/deep"));
        Files.createDirectories(repository.resolve("node_modules/pkg"));
        Files.write(repository.resolve("target/deep/huge.bin"), new byte[2 * 1024 * 1024]);
        Files.write(repository.resolve("node_modules/pkg/huge.bin"), new byte[2 * 1024 * 1024]);
        WorkspaceStateDigest digest = digest(repository, temporary.resolve("sessions"));

        String baseline = digest.capture();
        Files.write(repository.resolve("target/deep/huge.bin"), new byte[3 * 1024 * 1024]);
        Files.writeString(repository.resolve("node_modules/pkg/new.js"), "ignored");
        assertThat(digest.capture()).isEqualTo(baseline);

        Files.writeString(repository.resolve("tracked.txt"), "working\n");
        String working = digest.capture();
        assertThat(working).isNotEqualTo(baseline);
        git(repository, "add", "tracked.txt");
        String staged = digest.capture();
        assertThat(staged).isNotEqualTo(working);

        Files.writeString(repository.resolve("untracked.txt"), "one\n");
        String untracked = digest.capture();
        Files.writeString(repository.resolve("untracked.txt"), "two\n");
        assertThat(digest.capture()).isNotEqualTo(untracked);
        Files.delete(repository.resolve("tracked.txt"));
        assertThat(digest.capture()).isNotEqualTo(staged);
    }

    @Test
    void repositoryDigestDoesNotReadTrackedSensitiveContentButStillBindsGitState() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("repository-sensitive"));
        git(repository, "init");
        git(repository, "config", "user.name", "Fixture");
        git(repository, "config", "user.email", "fixture@example.invalid");
        Files.writeString(repository.resolve("credential-fixture.txt"), "first-value\n");
        git(repository, "add", "credential-fixture.txt");
        git(repository, "commit", "-m", "base");
        WorkspaceGuard guard = new WorkspaceGuard(repository);
        WorkspaceStateDigest digest = new WorkspaceStateDigest(guard, temporary.resolve("sessions-sensitive"),
                new WorkspaceStateDigest.ReadObserver() {
                    @Override public void afterRead(String ignored) { }
                    @Override public void beforeContentRead(String protocolPath) {
                        if (protocolPath.equals("credential-fixture.txt")) {
                            throw new AssertionError("敏感路径正文不得由 Workspace digest 打开");
                        }
                    }
                });

        String clean = digest.capture();
        Files.writeString(repository.resolve("credential-fixture.txt"), "second-value\n");

        assertThat(digest.capture()).isNotEqualTo(clean);
    }

    @Test
    void plainWorkspaceIsBoundedAndExcludesInternalSessionTree() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("plain"));
        Path sessions = Files.createDirectories(workspace.resolve(".cc-java/sessions"));
        Files.writeString(workspace.resolve("visible.txt"), "one");
        Files.writeString(sessions.resolve("journal.jsonl"), "internal-one");
        WorkspaceStateDigest digest = digest(workspace, sessions);
        String baseline = digest.capture();
        Files.writeString(sessions.resolve("journal.jsonl"), "internal-two");
        assertThat(digest.capture()).isEqualTo(baseline);
        Files.writeString(workspace.resolve("visible.txt"), "two");
        assertThat(digest.capture()).isNotEqualTo(baseline);
    }

    @Test
    void rejectsLinkEscapeAndConcurrentReplacement() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("unsafe"));
        git(repository, "init");
        Path external = temporary.resolve("external.txt");
        Files.writeString(external, "outside");
        try {
            Files.createSymbolicLink(repository.resolve("escape.txt"), external);
        } catch (UnsupportedOperationException | java.io.IOException denied) {
            org.junit.jupiter.api.Assumptions.abort("当前系统不能创建 symbolic link");
        }
        assertThatThrownBy(() -> digest(repository, temporary.resolve("sessions-link")).capture())
                .isInstanceOf(WorkspaceStateDigest.WorkspaceDigestException.class);

        Files.delete(repository.resolve("escape.txt"));
        Files.writeString(repository.resolve("race.txt"), "before");
        WorkspaceGuard guard = new WorkspaceGuard(repository);
        AtomicBoolean changed = new AtomicBoolean();
        WorkspaceStateDigest racing = new WorkspaceStateDigest(guard, temporary.resolve("sessions-race"), path -> {
            if (path.equals("race.txt") && changed.compareAndSet(false, true)) {
                Files.writeString(repository.resolve("race.txt"), "after-with-different-size");
            }
        });
        assertThatThrownBy(racing::capture)
                .isInstanceOf(WorkspaceStateDigest.WorkspaceDigestException.class);
    }


    @Test
    void rejectsPathAndIndexMutationAfterInitialGitEnumeration() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("git-input-race"));
        git(repository, "init");
        Files.writeString(repository.resolve("tracked.txt"), "before" + System.lineSeparator());
        git(repository, "add", "tracked.txt");
        WorkspaceGuard guard = new WorkspaceGuard(repository);
        AtomicBoolean changed = new AtomicBoolean();
        WorkspaceStateDigest racing = new WorkspaceStateDigest(guard, temporary.resolve("sessions-input-race"),
                new WorkspaceStateDigest.ReadObserver() {
                    @Override public void afterRead(String ignored) { }
                    @Override public void afterRepositoryHash() throws java.io.IOException {
                        if (changed.compareAndSet(false, true)) {
                            Files.writeString(repository.resolve("appeared.txt"), "new" + System.lineSeparator());
                            try {
                                git(repository, "add", "appeared.txt");
                            } catch (Exception failure) {
                                throw new java.io.IOException(failure);
                            }
                        }
                    }
                });

        assertThatThrownBy(racing::capture)
                .isInstanceOf(WorkspaceStateDigest.WorkspaceDigestException.class);
    }

    private static WorkspaceStateDigest digest(Path workspace, Path sessions) throws Exception {
        return new WorkspaceStateDigest(new WorkspaceGuard(workspace), sessions);
    }

    private static void git(Path directory, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("Fixture Git failed: " + output);
    }
}
