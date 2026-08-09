package io.github.liumaishenjian.ccjava.cli.extensions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** 验证固定来源、项目精确指纹信任和配置变化失效。 */
class ExtensionConfigurationLoaderTest {

    @Test
    void untrustedProjectEntriesCannotShadowTrustedUserEntriesWithTheSameName(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path projectDirectory = Files.createDirectory(workspace.resolve(".cc-java"));
        Path home = Files.createDirectories(root.resolve("home").resolve(".cc-java"));
        Path userHook = root.resolve("user-hook.exe").toAbsolutePath();
        Path projectHook = root.resolve("project-hook.exe").toAbsolutePath();
        var json = JsonMapper.builder().build();
        Files.write(home.resolve("extensions.json"), json.writeValueAsBytes(Map.of(
                "version", 1,
                "hooks", List.of(Map.of(
                        "id", "same",
                        "event", "PRE_TOOL",
                        "failurePolicy", "FAIL_OPEN",
                        "timeoutMs", 100,
                        "command", List.of(userHook.toString()))))));
        Files.write(projectDirectory.resolve("extensions.json"), json.writeValueAsBytes(Map.of(
                "version", 1,
                "hooks", List.of(Map.of(
                        "id", "same",
                        "event", "PRE_TOOL",
                        "failurePolicy", "FAIL_CLOSED",
                        "timeoutMs", 100,
                        "command", List.of(projectHook.toString()))))));

        try (ExtensionRuntime runtime = new ExtensionConfigurationLoader(
                root.resolve("home"), new WorkspaceGuard(workspace)).load()) {
            assertThat(runtime.status().projectTrusted()).isFalse();
            assertThat(runtime.status().hookCount()).isOne();
            assertThat(runtime.status().diagnosticCode()).contains("PROJECT_TRUST_REQUIRED");
        }
    }

    @Test
    void projectHooksStayInactiveUntilExactFingerprintIsExplicitlyTrusted(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path projectDirectory = Files.createDirectory(workspace.resolve(".cc-java"));
        Path home = Files.createDirectory(root.resolve("home"));
        Files.write(projectDirectory.resolve("extensions.json"), JsonMapper.builder().build().writeValueAsBytes(Map.of(
                "version", 1,
                "hooks", List.of(Map.of(
                        "id", "guard",
                        "event", "PRE_TOOL",
                        "failurePolicy", "FAIL_CLOSED",
                        "timeoutMs", 100,
                        "command", List.of(root.resolve("hook.exe").toAbsolutePath().toString()))))));
        ExtensionConfigurationLoader loader = new ExtensionConfigurationLoader(home, new WorkspaceGuard(workspace));

        try (ExtensionRuntime before = loader.load()) {
            assertThat(before.status().projectPresent()).isTrue();
            assertThat(before.status().projectTrusted()).isFalse();
            assertThat(before.status().hookCount()).isZero();
            assertThat(before.status().diagnosticCode()).contains("PROJECT_TRUST_REQUIRED");
        }
        var approval = loader.approveProject();
        assertThat(approval.successful()).isTrue();
        try (ExtensionRuntime after = loader.load()) {
            assertThat(after.status().projectTrusted()).isTrue();
            assertThat(after.status().hookCount()).isOne();
        }

        Files.writeString(projectDirectory.resolve("extensions.json"), "{\"version\":1}");
        try (ExtensionRuntime changed = loader.load()) {
            assertThat(changed.status().projectTrusted()).isFalse();
            assertThat(changed.status().diagnosticCode()).contains("PROJECT_TRUST_REQUIRED");
        }
    }
}
