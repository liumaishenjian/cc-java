package io.github.liumaishenjian.ccjava.cli.instructions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionLoadResult;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionActivation;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionCandidate;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionScopeKind;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionSourceKind;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceInstructionLoaderTest {

    @TempDir
    Path temporary;

    @Test
    void loadsProjectAndDirectoryCandidatesOnlyInsideWorkspace() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.writeString(workspace.resolve("AGENTS.md"), "project");
        Files.createDirectories(workspace.resolve("module"));
        Files.writeString(workspace.resolve("module/AGENTS.md"), "directory");
        WorkspaceInstructionLoader loader = loader(workspace);

        assertThat(loader.load(candidate(InstructionSourceKind.PROJECT, "AGENTS.md"), CancellationToken.none())
                .loaded()).isPresent();
        assertThat(loader.load(candidate(InstructionSourceKind.DIRECTORY, "module/AGENTS.md"), CancellationToken.none())
                .loaded()).isPresent();
        assertThatThrownBy(() -> candidate(InstructionSourceKind.DIRECTORY, "../outside.md"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCandidateWhoseFixedSourceKindAndLogicalPathDoNotMatch() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.writeString(workspace.resolve("AGENTS.md"), "project");
        WorkspaceInstructionLoader loader = loader(workspace);

        assertFailure(loader.load(candidate(InstructionSourceKind.PROJECT, "other.md"), CancellationToken.none()),
                InstructionDiagnosticCode.UNREADABLE);
        assertFailure(loader.load(candidate(InstructionSourceKind.LOCAL, "AGENTS.md"), CancellationToken.none()),
                InstructionDiagnosticCode.UNREADABLE);
        assertFailure(loader.load(candidate(InstructionSourceKind.DIRECTORY, "AGENTS.md"), CancellationToken.none()),
                InstructionDiagnosticCode.UNREADABLE);
    }

    @Test
    void rejectsSameSizeMutationAfterReadWithoutPublishingEitherBody() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path agents = Files.writeString(workspace.resolve("AGENTS.md"), "before");
        Files.setLastModifiedTime(agents, FileTime.from(Instant.parse("2026-08-06T00:00:00Z")));

        InstructionLoadResult result = WorkspaceInstructionLoader.read(agents, CancellationToken.none(), () -> {
            try {
                Files.writeString(agents, "after!");
                Files.setLastModifiedTime(agents, FileTime.from(Instant.parse("2026-08-06T00:00:01Z")));
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        });

        assertFailure(result, InstructionDiagnosticCode.IDENTITY_CHANGED);
        assertThat(result.toString()).doesNotContain("before", "after!");
    }

    @Test
    void rejectsInvalidTextAndLimitsWithoutPublishingContent() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        WorkspaceInstructionLoader loader = loader(workspace);
        Path agents = workspace.resolve("AGENTS.md");

        Files.write(agents, new byte[] {'x', 0, 'y'});
        assertFailure(loader.load(candidate(InstructionSourceKind.PROJECT, "AGENTS.md"), CancellationToken.none()),
                InstructionDiagnosticCode.UNREADABLE);

        Files.write(agents, new byte[WorkspaceInstructionLoader.MAX_BYTES + 1]);
        assertFailure(loader.load(candidate(InstructionSourceKind.PROJECT, "AGENTS.md"), CancellationToken.none()),
                InstructionDiagnosticCode.LIMIT_EXCEEDED);

        Files.writeString(agents, "line\n".repeat(WorkspaceInstructionLoader.MAX_LINES));
        assertFailure(loader.load(candidate(InstructionSourceKind.PROJECT, "AGENTS.md"), CancellationToken.none()),
                InstructionDiagnosticCode.LIMIT_EXCEEDED);
    }

    @Test
    void localCandidateRequiresExplicitGitIgnoreProof() throws Exception {
        assumeGitAvailable();
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        initializeRepository(workspace);
        Files.createDirectories(workspace.resolve(".cc-java"));
        Files.writeString(workspace.resolve(".cc-java/AGENTS.local.md"), "local");
        WorkspaceInstructionLoader loader = loader(workspace);

        assertFailure(loader.load(candidate(InstructionSourceKind.LOCAL, ".cc-java/AGENTS.local.md"),
                CancellationToken.none()), InstructionDiagnosticCode.LOCAL_INSTRUCTIONS_NOT_GITIGNORED);

        Files.writeString(workspace.resolve(".gitignore"), ".cc-java/AGENTS.local.md\n");
        assertThat(loader.load(candidate(InstructionSourceKind.LOCAL, ".cc-java/AGENTS.local.md"),
                CancellationToken.none()).loaded()).isPresent();
    }

    @Test
    void rejectsExternalSymlinkWhenPlatformAllowsCreation() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path outside = Files.writeString(temporary.resolve("outside.md"), "private");
        try {
            Files.createSymbolicLink(workspace.resolve("AGENTS.md"), outside);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            Assumptions.abort("当前环境不能创建 Symlink: " + exception.getClass().getSimpleName());
        }

        assertFailure(loader(workspace).load(candidate(InstructionSourceKind.PROJECT, "AGENTS.md"),
                CancellationToken.none()), InstructionDiagnosticCode.UNREADABLE);
        assertThat(Files.exists(outside)).isTrue();
    }

    @Test
    void rejectsInternalSymbolicLinkRatherThanFollowingItInsideWorkspace() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("instructions.md"), "private");
        try {
            Files.createSymbolicLink(workspace.resolve("AGENTS.md"), target.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            Assumptions.abort("当前环境不能创建 Symlink: " + exception.getClass().getSimpleName());
        }

        assertFailure(loader(workspace).load(candidate(InstructionSourceKind.PROJECT, "AGENTS.md"),
                CancellationToken.none()), InstructionDiagnosticCode.UNREADABLE);
    }

    @Test
    void cancellationFailsClosedBeforeFileRead() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.writeString(workspace.resolve("AGENTS.md"), "project");
        CancellationSource cancellation = new CancellationSource();
        cancellation.cancel();

        assertFailure(loader(workspace).load(candidate(InstructionSourceKind.PROJECT, "AGENTS.md"),
                cancellation.token()), InstructionDiagnosticCode.CANCELLED);
    }

    private WorkspaceInstructionLoader loader(Path workspace) throws Exception {
        WorkspaceGuard guard = new WorkspaceGuard(workspace);
        return new WorkspaceInstructionLoader(guard, new GitIgnorePolicy(guard.workspace()));
    }

    private static InstructionCandidate candidate(InstructionSourceKind source, String safeId) {
        InstructionScopeKind scope = source == InstructionSourceKind.DIRECTORY
                ? InstructionScopeKind.DIRECTORY_SUBTREE : InstructionScopeKind.WORKSPACE;
        return new InstructionCandidate(source, scope, safeId, 0,
                source == InstructionSourceKind.DIRECTORY
                        ? InstructionActivation.VERIFIED_TARGET : InstructionActivation.STARTUP);
    }

    private static void assertFailure(InstructionLoadResult result, InstructionDiagnosticCode code) {
        assertThat(result.loaded()).isEmpty();
        assertThat(result.failureCode()).contains(code);
        assertThat(result.toString()).doesNotContain("private", "outside.md");
    }

    private static void assumeGitAvailable() {
        try {
            Assumptions.assumeTrue(new ProcessBuilder("git", "--version").start().waitFor() == 0, "Git 不可用");
        } catch (Exception exception) {
            Assumptions.abort("Git 不可用");
        }
    }

    private static void initializeRepository(Path workspace) throws Exception {
        assertThat(new ProcessBuilder("git", "init", "--quiet", workspace.toString()).start().waitFor()).isZero();
    }
}
