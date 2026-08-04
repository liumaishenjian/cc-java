package io.github.liumaishenjian.ccjava.tools.local.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 从规范 Workspace 派生隐私安全 repository-id 与默认文件记忆目录。
 *
 * <p>Repository ID 是完整 SHA-256 十六进制摘要，不包含原始路径片段；目录结构固定为
 * {@code <home>/.cc-java/projects/<repository-id>/memory}。该应用层布局不是 OS Sandbox。</p>
 *
 * @since 0.7.0
 */
public final class MemoryStorageLayout {

    /** 创建无状态布局工具。 */
    public MemoryStorageLayout() {
    }

    /**
     * 从真实 Workspace 身份稳定派生 repository-id。
     *
     * @param workspace 已存在的 Workspace
     * @return 64 位小写十六进制摘要
     * @throws IOException Workspace 无法解析时
     */
    public String repositoryId(Path workspace) throws IOException {
        Path canonical = Objects.requireNonNull(workspace, "workspace 不能为空").toRealPath();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    /**
     * 解析默认 memory root，但不创建目录。
     *
     * @param home 用户 home 根目录
     * @param repositoryId 已派生 repository-id
     * @return 规范化默认路径
     */
    public Path defaultMemoryRoot(Path home, String repositoryId) {
        Objects.requireNonNull(home, "home 不能为空");
        String checked = Objects.requireNonNull(repositoryId, "repositoryId 不能为空");
        if (!checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("repositoryId 必须是 SHA-256 十六进制摘要");
        }
        return home.toAbsolutePath().normalize()
                .resolve(".cc-java")
                .resolve("projects")
                .resolve(checked)
                .resolve("memory");
    }
}
