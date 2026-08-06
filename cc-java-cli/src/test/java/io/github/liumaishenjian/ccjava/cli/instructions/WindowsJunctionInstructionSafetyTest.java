package io.github.liumaishenjian.ccjava.cli.instructions;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Windows Junction 真实文件系统证据；策略禁止创建 Junction 时仅跳过该补充测试。 */
@EnabledOnOs(OS.WINDOWS)
class WindowsJunctionInstructionSafetyTest {

    @TempDir
    Path temporary;

    @Test
    void rejectsJunctionAsUserRootAndWorkspaceCandidateBeforeGitStarts() throws Exception {
        Path outsideRoot = Files.createDirectories(temporary.resolve("outside-root"));
        Files.writeString(outsideRoot.resolve("AGENTS.md"), "outside");
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path userLink = Files.createDirectories(home.resolve(".cc-java")).resolve("instructions");
        createJunction(userLink, outsideRoot);

        try {
            assertThat(new UserInstructionRootGuard(home).load().diagnostic())
                    .contains(UserInstructionRootGuard.UserInstructionDiagnostic.ROOT_LINK_OR_TYPE);
        } finally {
            Files.deleteIfExists(userLink);
        }

        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path outsideCandidateDirectory = Files.createDirectory(temporary.resolve("outside-candidate"));
        Files.writeString(outsideCandidateDirectory.resolve("AGENTS.local.md"), "outside");
        Path candidateLink = Files.createDirectories(workspace.resolve(".cc-java")).resolve("AGENTS.local.md");
        createJunction(candidateLink, outsideCandidateDirectory);
        try {
            assertThat(new GitIgnorePolicy(workspace, builder -> {
                throw new AssertionError("unsafe junction must reject before Git");
            }).allowsFixedLocalInstructions()).isFalse();
        } finally {
            Files.deleteIfExists(candidateLink);
        }
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
