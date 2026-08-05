package io.github.liumaishenjian.ccjava.cli.instructions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.instructions.InstructionActivation;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionSourceKind;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstructionCandidatePlannerTest {
    @TempDir
    Path temporary;

    @Test
    void plansFileParentAndDirectorySelfFarToNearWithoutRepeatingRoot() throws Exception {
        Path workspace = workspace();
        Files.createDirectories(workspace.resolve("a/b"));
        Files.writeString(workspace.resolve("a/b/source.java"), "x");
        InstructionCandidatePlanner planner = new InstructionCandidatePlanner();
        WorkspaceGuard guard = new WorkspaceGuard(workspace);

        var result = planner.plan(List.of(
                VerifiedInstructionTarget.file(guard, "a/b/source.java"),
                VerifiedInstructionTarget.directory(guard, "a")));

        assertThat(result).extracting(candidate -> candidate.safeSourceId())
                .containsExactly("user-instructions", "AGENTS.md", "a/AGENTS.md", "a/b/AGENTS.md", ".cc-java/AGENTS.local.md");
        assertThat(result).extracting(candidate -> candidate.sourceKind())
                .containsExactly(InstructionSourceKind.USER, InstructionSourceKind.PROJECT,
                        InstructionSourceKind.DIRECTORY, InstructionSourceKind.DIRECTORY,
                        InstructionSourceKind.LOCAL);
        assertThat(result).extracting(candidate -> candidate.activation())
                .containsExactly(InstructionActivation.STARTUP, InstructionActivation.STARTUP,
                        InstructionActivation.VERIFIED_TARGET, InstructionActivation.VERIFIED_TARGET,
                        InstructionActivation.STARTUP);
    }

    @Test
    void preservesFirstSeenUnionAcrossMultipleTargetsAndIsDeterministic() throws Exception {
        Path workspace = workspace();
        Files.createDirectories(workspace.resolve("x/y"));
        Files.createDirectories(workspace.resolve("a/b"));
        WorkspaceGuard guard = new WorkspaceGuard(workspace);
        InstructionCandidatePlanner planner = new InstructionCandidatePlanner();
        List<VerifiedInstructionTarget> targets = List.of(
                VerifiedInstructionTarget.directory(guard, "x/y"),
                VerifiedInstructionTarget.directory(guard, "a/b"));

        var first = planner.plan(targets);
        var second = planner.plan(targets);

        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(candidate -> candidate.safeSourceId())
                .containsExactly("user-instructions", "AGENTS.md", "a/AGENTS.md", "x/AGENTS.md", "a/b/AGENTS.md", "x/y/AGENTS.md",
                        ".cc-java/AGENTS.local.md");
        assertThat(first).extracting(candidate -> candidate.precedence())
                .containsExactly(0, 1, 2, 3, 4, 5, 6);
    }

    @Test
    void sortsMultipleTargetDirectoriesByDepthThenStableByteOrder() throws Exception {
        Path workspace = workspace();
        Files.createDirectories(workspace.resolve("z/deep"));
        Files.createDirectories(workspace.resolve("a/deep"));
        WorkspaceGuard guard = new WorkspaceGuard(workspace);

        var result = new InstructionCandidatePlanner().plan(List.of(
                VerifiedInstructionTarget.directory(guard, "z/deep"),
                VerifiedInstructionTarget.directory(guard, "a/deep")));

        assertThat(result).extracting(candidate -> candidate.safeSourceId())
                .containsExactly("user-instructions", "AGENTS.md", "a/AGENTS.md", "z/AGENTS.md",
                        "a/deep/AGENTS.md", "z/deep/AGENTS.md", ".cc-java/AGENTS.local.md");
    }

    @Test
    void acceptsRootFileAndDirectoryWithoutDirectoryCandidate() throws Exception {
        Path workspace = workspace();
        Files.writeString(workspace.resolve("readme.txt"), "x");
        WorkspaceGuard guard = new WorkspaceGuard(workspace);

        assertThat(new InstructionCandidatePlanner().plan(List.of(
                VerifiedInstructionTarget.file(guard, "readme.txt"),
                VerifiedInstructionTarget.directory(guard, "."))))
                .extracting(candidate -> candidate.safeSourceId())
                .containsExactly("user-instructions", "AGENTS.md", ".cc-java/AGENTS.local.md");
    }

    @Test
    void rejectsUnverifiedMissingAndOutsideTargetsAtAdapterBoundary() throws Exception {
        Path workspace = workspace();
        WorkspaceGuard guard = new WorkspaceGuard(workspace);
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "x");

        assertThatThrownBy(() -> VerifiedInstructionTarget.file(guard, "missing.txt"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> VerifiedInstructionTarget.file(guard, "../" + outside.getFileName()))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> new InstructionCandidatePlanner().plan(List.of((VerifiedInstructionTarget) null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void permitsDepthEightAndRejectsDepthNine() throws Exception {
        Path workspace = workspace();
        String eight = "a/b/c/d/e/f/g/h";
        String nine = eight + "/i";
        Files.createDirectories(workspace.resolve(eight));
        Files.createDirectories(workspace.resolve(nine));
        WorkspaceGuard guard = new WorkspaceGuard(workspace);
        InstructionCandidatePlanner planner = new InstructionCandidatePlanner();

        assertThat(planner.plan(List.of(VerifiedInstructionTarget.directory(guard, eight))))
                .extracting(candidate -> candidate.safeSourceId()).contains("a/b/c/d/e/f/g/h/AGENTS.md");
        assertThatThrownBy(() -> planner.plan(List.of(VerifiedInstructionTarget.directory(guard, nine))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Path workspace() throws Exception {
        return Files.createDirectory(temporary.resolve("workspace"));
    }
}
