package io.github.liumaishenjian.ccjava.tools.local.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Windows NTFS Junction 安全证据；系统策略禁止创建 Junction 时明确跳过。 */
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
            assertThat(guard.requireNewFile("internal-link/new.txt").realPath())
                    .isEqualTo(internalTarget.toRealPath().resolve("new.txt"));
            assertThatThrownBy(() -> guard.requireRegularFile("external-link/outside.txt"))
                    .isInstanceOf(WorkspaceAccessException.class)
                    .satisfies(exception -> assertThat(((WorkspaceAccessException) exception)
                            .error().code()).isEqualTo(ToolErrorCode.LINK_ESCAPE));
            assertThatThrownBy(() -> guard.requireNewFile("external-link/new.txt"))
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
        byte[] outputBytes = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            String output = decodeOutput(outputBytes);
            Assumptions.assumeTrue(
                    isAccessDenied(output),
                    () -> "Junction 创建出现非权限类错误：exit=" + exit + ", output=" + output);
            Assumptions.abort(
                    "当前 Windows 策略禁止创建 Junction，跳过本机能力证据：" + output);
        }
        assertThat(Files.isDirectory(link)).isTrue();
    }

    private static String decodeOutput(byte[] bytes) {
        LinkedHashSet<Charset> candidates = new LinkedHashSet<>();
        String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding != null) {
            candidates.add(Charset.forName(nativeEncoding));
        }
        candidates.add(Charset.defaultCharset());
        candidates.add(Charset.forName("GBK"));
        candidates.add(StandardCharsets.UTF_8);
        return candidates.stream()
                .map(charset -> new String(bytes, charset).trim())
                .filter(value -> !value.isEmpty())
                .min(java.util.Comparator.comparingLong(value -> value.chars()
                        .filter(character -> character == '�').count()))
                .orElse("");
    }

    private static boolean isAccessDenied(String output) {
        String normalized = output.toLowerCase(Locale.ROOT);
        return normalized.contains("access is denied")
                || normalized.contains("access denied")
                || normalized.contains("拒绝访问")
                || normalized.contains("客户端没有所需的特权")
                || normalized.contains("privilege is not held");
    }
}
