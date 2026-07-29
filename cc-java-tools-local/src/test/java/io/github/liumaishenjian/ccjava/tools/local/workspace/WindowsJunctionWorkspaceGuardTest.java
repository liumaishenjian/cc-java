package io.github.liumaishenjian.ccjava.tools.local.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Windows NTFS Junction 的不可跳过本机安全证据。 */
@EnabledOnOs(OS.WINDOWS)
class WindowsJunctionWorkspaceGuardTest {

    @TempDir
    Path temporary;

    @Test
    void allowsInternalJunctionAndRejectsExternalJunctionWithoutFollowingOnCleanup()
            throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path internalTarget = Files.createDirectory(workspace.resolve("internal-target"));
        Files.writeString(internalTarget.resolve("inside.txt"), "inside");
        Path externalTarget = Files.createDirectory(temporary.resolve("external-target"));
        Files.writeString(externalTarget.resolve("outside.txt"), "outside");
        Path internalLink = workspace.resolve("internal-link");
        Path externalLink = workspace.resolve("external-link");
        createJunction(internalLink, internalTarget);
        createJunction(externalLink, externalTarget);
        WorkspaceGuard guard = new WorkspaceGuard(workspace);

        try {
            assertThat(guard.requireRegularFile("internal-link/inside.txt").realPath())
                    .isEqualTo(internalTarget.resolve("inside.txt").toRealPath());
            assertThatThrownBy(() -> guard.requireRegularFile("external-link/outside.txt"))
                    .isInstanceOf(WorkspaceAccessException.class)
                    .satisfies(exception -> assertThat(((WorkspaceAccessException) exception)
                            .error().code()).isEqualTo(ToolErrorCode.LINK_ESCAPE));
        } finally {
            // Junction 本身先删除；绝不能递归删除它而跟随到 Workspace 外目标。
            Files.deleteIfExists(externalLink);
            Files.deleteIfExists(internalLink);
        }
        assertThat(Files.exists(externalTarget.resolve("outside.txt"))).isTrue();
    }

    private static void createJunction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder(
                "cmd.exe", "/d", "/c", "mklink", "/J",
                link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.Charset.defaultCharset());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("无法创建 Junction，exit=" + exit + ", output=" + output);
        }
    }
}
