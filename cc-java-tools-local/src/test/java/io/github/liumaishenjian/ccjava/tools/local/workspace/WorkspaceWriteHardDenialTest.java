package io.github.liumaishenjian.ccjava.tools.local.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceWriteHardDenialTest {

    @TempDir
    Path temporary;

    @Test
    void rejectsExternalLinkBeforeApprovalWhenPlatformAllowsCreation() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path outsideFile = Files.writeString(temporary.resolve("outside.txt"), "outside");
        Path outsideDirectory = Files.createDirectory(temporary.resolve("outside-directory"));
        Path fileLink = workspace.resolve("file-link.txt");
        Path directoryLink = workspace.resolve("directory-link");
        try {
            Files.createSymbolicLink(fileLink, outsideFile);
            Files.createSymbolicLink(directoryLink, outsideDirectory);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            Assumptions.abort(
                    "当前环境不能创建 Symlink: " + exception.getClass().getSimpleName());
        }
        WorkspaceWriteHardDenial denial = new WorkspaceWriteHardDenial(
                new WorkspaceGuard(workspace));

        assertThat(denial.test(new PermissionSelector(
                "apply_patch", ToolSource.BUILT_IN, "file-link.txt"))).isTrue();
        assertThat(denial.test(new PermissionSelector(
                "write_file", ToolSource.BUILT_IN, "directory-link/new.txt"))).isTrue();
        assertThat(denial.test(new PermissionSelector(
                "write_file", ToolSource.BUILT_IN, "new.txt"))).isFalse();
    }

    @Test
    void letsAdapterHandleCorrectableExistenceAndConflictErrors() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.writeString(workspace.resolve("existing.txt"), "existing");
        WorkspaceWriteHardDenial denial = new WorkspaceWriteHardDenial(
                new WorkspaceGuard(workspace));

        assertThat(denial.test(new PermissionSelector(
                "apply_patch", ToolSource.BUILT_IN, "missing.txt"))).isFalse();
        assertThat(denial.test(new PermissionSelector(
                "write_file", ToolSource.BUILT_IN, "existing.txt"))).isFalse();
        assertThat(denial.test(new PermissionSelector(
                "write_file", ToolSource.BUILT_IN, ".git/config"))).isTrue();
    }
}
