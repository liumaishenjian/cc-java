package io.github.liumaishenjian.ccjava.tools.local.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceGuardTest {

    @TempDir
    Path temporary;

    @Test
    void acceptsExistingRelativeFileAndUsesProtocolPath() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.createDirectories(workspace.resolve("src/main"));
        Files.writeString(workspace.resolve("src/main/App.java"), "class App {}");
        WorkspaceGuard guard = new WorkspaceGuard(workspace);

        ValidatedWorkspacePath result = guard.requireRegularFile("src\\main/./App.java");

        assertThat(result.protocolPath()).isEqualTo("src/main/App.java");
        assertThat(result.realPath()).isEqualTo(workspace.resolve("src/main/App.java").toRealPath());
    }

    @Test
    void rejectsAbsoluteDriveUncAndTraversalPaths() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        WorkspaceGuard guard = new WorkspaceGuard(workspace);

        assertCode(guard, temporary.resolve("outside.txt").toString(), ToolErrorCode.INVALID_PATH);
        assertCode(guard, "C:outside.txt", ToolErrorCode.INVALID_PATH);
        assertCode(guard, "\\\\server\\share\\file", ToolErrorCode.INVALID_PATH);
        assertCode(guard, "../outside.txt", ToolErrorCode.WORKSPACE_BOUNDARY_VIOLATION);
        assertCode(guard, "safe/../file.txt", ToolErrorCode.WORKSPACE_BOUNDARY_VIOLATION);
    }

    @Test
    void rejectsSensitiveFilesButAllowsTemplates() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.createDirectories(workspace.resolve("config"));
        Files.writeString(workspace.resolve(".env"), "SECRET=value");
        Files.writeString(workspace.resolve(".env.production"), "SECRET=value");
        Files.writeString(workspace.resolve(".env.example"), "SECRET=");
        Files.writeString(workspace.resolve("config/provider.local.properties"), "api-key=x");
        Files.writeString(workspace.resolve("config/provider.local.properties.example"), "api-key=");
        WorkspaceGuard guard = new WorkspaceGuard(workspace);

        assertCode(guard, ".env", ToolErrorCode.SENSITIVE_PATH);
        assertCode(guard, ".env.production", ToolErrorCode.SENSITIVE_PATH);
        assertCode(guard, "config/provider.local.properties", ToolErrorCode.SENSITIVE_PATH);
        assertThat(guard.requireRegularFile(".env.example").protocolPath())
                .isEqualTo(".env.example");
        assertThat(guard.requireRegularFile("config/provider.local.properties.example").protocolPath())
                .isEqualTo("config/provider.local.properties.example");
    }

    @Test
    void rejectsExternalSymbolicLinkWhenPlatformAllowsCreation() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "outside");
        Path link = workspace.resolve("link.txt");
        Path outsideDirectory = Files.createDirectory(temporary.resolve("outside-dir"));
        Path directoryLink = workspace.resolve("directory-link");
        try {
            Files.createSymbolicLink(link, outside);
            Files.createSymbolicLink(directoryLink, outsideDirectory);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            org.junit.jupiter.api.Assumptions.abort("当前环境不能创建 Symlink: " + exception.getClass().getSimpleName());
        }
        WorkspaceGuard guard = new WorkspaceGuard(workspace);

        assertCode(guard, "link.txt", ToolErrorCode.LINK_ESCAPE);
        assertCode(guard, "directory-link/new.txt", ToolErrorCode.LINK_ESCAPE, true);
    }

    @Test
    void acceptsOnlyNewFileWhoseDirectParentAlreadyExists() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-new"));
        Files.createDirectories(workspace.resolve("src/main"));
        WorkspaceGuard guard = new WorkspaceGuard(workspace);

        ValidatedWorkspacePath target = guard.requireNewFile("src\\main/New.java");

        assertThat(target.protocolPath()).isEqualTo("src/main/New.java");
        assertThat(target.realPath()).isEqualTo(
                workspace.resolve("src/main").toRealPath().resolve("New.java"));
        assertCode(guard, "missing/New.java", ToolErrorCode.PATH_NOT_FOUND, true);
        Files.writeString(workspace.resolve("existing.txt"), "user");
        assertCode(guard, "existing.txt", ToolErrorCode.FILE_CONFLICT, true);
        assertCode(guard, ".env", ToolErrorCode.SENSITIVE_PATH, true);
    }

    private static void assertCode(
            WorkspaceGuard guard,
            String path,
            ToolErrorCode expected) {
        assertThatThrownBy(() -> guard.requireExisting(path))
                .isInstanceOf(WorkspaceAccessException.class)
                .satisfies(exception -> assertThat(((WorkspaceAccessException) exception)
                        .error().code()).isEqualTo(expected));
    }

    private static void assertCode(
            WorkspaceGuard guard,
            String path,
            ToolErrorCode expected,
            boolean newFile) {
        assertThatThrownBy(() -> {
            if (newFile) {
                guard.requireNewFile(path);
            } else {
                guard.requireExisting(path);
            }
        })
                .isInstanceOf(WorkspaceAccessException.class)
                .satisfies(exception -> assertThat(((WorkspaceAccessException) exception)
                        .error().code()).isEqualTo(expected));
    }
}
