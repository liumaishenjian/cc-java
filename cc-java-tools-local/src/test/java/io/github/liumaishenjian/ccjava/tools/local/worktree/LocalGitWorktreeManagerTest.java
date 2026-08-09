package io.github.liumaishenjian.ccjava.tools.local.worktree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.worktree.WorktreeDisposition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** S12 fixed-argv Git Worktree lease 的真实本机 Git 回归。 */
class LocalGitWorktreeManagerTest {
    @TempDir Path temp;

    @Test
    void createsEntersAndRemovesCleanLeaseWithoutRegistrationLeak() throws Exception {
        Path repository = repository(); String base = git(repository, "rev-parse", "HEAD").trim();
        LocalGitWorktreeManager manager = new LocalGitWorktreeManager(repository);
        var lease = manager.create("clean-task", base); Path child = manager.enter(lease);
        assertThat(child).isNotEqualTo(repository); assertThat(Files.exists(child.resolve("seed.txt"))).isTrue();
        manager.leave(lease); var removed = manager.removeClean(lease);
        assertThat(removed.disposition()).isEqualTo(WorktreeDisposition.REMOVED);
        assertThat(Files.exists(child)).isFalse();
        assertThat(git(repository, "worktree", "list", "--porcelain")).doesNotContain(child.toString());
        assertThat(git(repository, "branch", "--list", lease.branch())).isBlank();
    }

    @Test
    void preservesIgnoredActiveAndIdentityMismatchWork() throws Exception {
        Path repository = repository(); String base = git(repository, "rev-parse", "HEAD").trim();
        LocalGitWorktreeManager manager = new LocalGitWorktreeManager(repository);

        var ignored = manager.create("ignored-task", base); Path ignoredPath = manager.enter(ignored);
        Files.writeString(ignoredPath.resolve(".gitignore"), "ignored.tmp\n");
        git(ignoredPath, "add", ".gitignore");
        git(ignoredPath, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "ignore-rule");
        Files.writeString(ignoredPath.resolve("ignored.tmp"), "valuable"); manager.leave(ignored);
        assertThat(manager.removeClean(ignored).disposition()).isEqualTo(WorktreeDisposition.FAILED_PRESERVED);
        assertThat(Files.exists(ignoredPath.resolve("ignored.tmp"))).isTrue();

        var active = manager.create("active-task", base); Path activePath = manager.enter(active);
        assertThat(manager.removeClean(active).disposition()).isEqualTo(WorktreeDisposition.FAILED_PRESERVED);
        assertThat(Files.exists(activePath)).isTrue(); manager.leave(active);

        var mismatch = new io.github.liumaishenjian.ccjava.domain.worktree.WorktreeLease(active.id(),
                "0".repeat(64), active.baseCommit(), active.branch(), active.opaqueRoot(), active.disposition());
        assertThatThrownBy(() -> manager.enter(mismatch)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void crossManagerCannotUseUnrecoveredLeaseAndMissingRegistrationIsPreserved() throws Exception {
        Path repository = repository(); String base = git(repository, "rev-parse", "HEAD").trim();
        LocalGitWorktreeManager first = new LocalGitWorktreeManager(repository);
        var lease = first.create("cross-manager", base); Path child = first.enter(lease); first.leave(lease);
        LocalGitWorktreeManager second = new LocalGitWorktreeManager(repository);
        assertThatThrownBy(() -> second.enter(lease)).isInstanceOf(IllegalArgumentException.class);
        git(repository, "worktree", "remove", child.toString());
        assertThat(first.removeClean(lease).disposition()).isEqualTo(WorktreeDisposition.FAILED_PRESERVED);
    }

    @Test
    void preservesDirtyAndCommittedWorkAndRejectsUnsafeSlug() throws Exception {
        Path repository = repository(); String base = git(repository, "rev-parse", "HEAD").trim();
        LocalGitWorktreeManager manager = new LocalGitWorktreeManager(repository);
        assertThatThrownBy(() -> manager.create("../escape", base)).isInstanceOf(IllegalArgumentException.class);
        var dirty = manager.create("dirty-task", base); Path dirtyPath = manager.enter(dirty);
        Files.writeString(dirtyPath.resolve("valuable.txt"), "keep"); manager.leave(dirty);
        assertThat(manager.removeClean(dirty).disposition()).isEqualTo(WorktreeDisposition.FAILED_PRESERVED);
        assertThat(Files.exists(dirtyPath.resolve("valuable.txt"))).isTrue();

        var committed = manager.create("commit-task", base); Path commitPath = manager.enter(committed);
        Files.writeString(commitPath.resolve("commit.txt"), "keep"); git(commitPath, "add", "commit.txt");
        git(commitPath, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "fixture");
        manager.leave(committed);
        assertThat(manager.removeClean(committed).disposition()).isEqualTo(WorktreeDisposition.FAILED_PRESERVED);
        assertThat(Files.exists(commitPath)).isTrue();
    }

    private Path repository() throws Exception {
        Path repository = Files.createDirectories(temp.resolve("repo-" + java.util.UUID.randomUUID()));
        git(repository, "init"); Files.writeString(repository.resolve("seed.txt"), "seed"); git(repository, "add", "seed.txt");
        git(repository, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "base");
        return repository.toRealPath();
    }

    private static String git(Path cwd, String... args) throws Exception {
        var command = new java.util.ArrayList<String>(); command.add("git"); command.addAll(List.of(args));
        java.io.IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
                byte[] output = process.getInputStream().readAllBytes();
                if (process.waitFor() != 0) throw new AssertionError(new String(output));
                return new String(output, java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException transientWindowsLaunchFailure) {
                last = transientWindowsLaunchFailure;
                Thread.yield();
            }
        }
        throw last;
    }
}
