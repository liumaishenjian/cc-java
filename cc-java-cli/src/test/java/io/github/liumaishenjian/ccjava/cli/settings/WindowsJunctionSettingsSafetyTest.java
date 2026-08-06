package io.github.liumaishenjian.ccjava.cli.settings;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticCode;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Windows Junction 真实文件系统证据；策略禁止创建 Junction 时仅跳过该补充测试。 */
@EnabledOnOs(OS.WINDOWS)
class WindowsJunctionSettingsSafetyTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsJunctionAsUserRootBeforeSettingsParse() throws Exception {
        Path outsideRoot = Files.createDirectory(temporary.resolve("outside-root"));
        Files.writeString(outsideRoot.resolve("settings.json"), "{\"schemaVersion\":1}");
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path userRoot = home.resolve(".cc-java");
        createJunction(userRoot, outsideRoot);
        try {
            SettingsFixedSourceLoader.SettingsSourceLoadResult result = loader(home,
                    Files.createDirectory(temporary.resolve("workspace"))).loadUser(CancellationToken.none());

            assertThat(result.snapshot()).isEmpty();
            assertThat(result.diagnostics()).extracting(diagnostic -> diagnostic.code())
                    .containsExactly(ConfigurationDiagnosticCode.UNSAFE_FILE);
        } finally {
            Files.deleteIfExists(userRoot);
        }
    }

    @Test
    void rejectsProjectCandidateJunctionBeforeGitApproval() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path outsideRoot = Files.createDirectory(temporary.resolve("outside-root"));
        Files.writeString(outsideRoot.resolve("settings.json"), "{\"schemaVersion\":1}");
        Path candidate = Files.createDirectories(workspace.resolve(".cc-java")).resolve("settings.local.json");
        createJunction(candidate, outsideRoot);
        try {
            SettingsLocalGitIgnorePolicy gitPolicy = new SettingsLocalGitIgnorePolicy(workspace, builder -> {
                throw new AssertionError("unsafe junction must reject before Git approval");
            });
            SettingsFixedSourceLoader loader = new SettingsFixedSourceLoader(home, new WorkspaceGuard(workspace),
                    new SettingsV1SourceParser(Set.of("read_file")), gitPolicy);

            SettingsFixedSourceLoader.SettingsSourceLoadResult result = loader.loadProjectLocal(CancellationToken.none());

            assertThat(result.snapshot()).isEmpty();
            assertThat(result.diagnostics()).extracting(diagnostic -> diagnostic.code())
                    .containsExactly(ConfigurationDiagnosticCode.UNSAFE_FILE);
        } finally {
            Files.deleteIfExists(candidate);
        }
    }

    private static SettingsFixedSourceLoader loader(Path home, Path workspace) throws Exception {
        return new SettingsFixedSourceLoader(home, new WorkspaceGuard(workspace), new SettingsV1SourceParser(Set.of("read_file")));
    }

    private static void createJunction(Path link, Path target) throws Exception {
        Process process;
        try {
            process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                    link.toString(), target.toString()).redirectErrorStream(true).start();
        } catch (IOException exception) {
            Assumptions.abort("当前 Windows 策略禁止启动 Junction 创建进程");
            return;
        }
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            String message = new String(output, Charset.defaultCharset()).toLowerCase();
            Assumptions.assumeTrue(message.contains("denied") || message.contains("拒绝")
                    || message.contains("privilege"), "Junction 创建失败：" + message);
            Assumptions.abort("当前 Windows 策略禁止创建 Junction");
        }
    }
}
