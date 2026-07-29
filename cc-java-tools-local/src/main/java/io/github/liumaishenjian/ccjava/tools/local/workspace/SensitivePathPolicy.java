package io.github.liumaishenjian.ccjava.tools.local.workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * S03 固定的敏感路径拒绝策略。
 *
 * <p>策略同时应用于模型提供的逻辑路径和解析后的真实相对路径，避免允许路径通过链接指向
 * 敏感目标。它只按名称拒绝高置信风险文件，不扫描正文，也不能由 Prompt 或
 * {@code AGENTS.md} 放宽。</p>
 *
 * @since 0.3.0
 */
public final class SensitivePathPolicy {

    /** 创建 S03 固定敏感路径策略。 */
    public SensitivePathPolicy() {
    }

    private static final List<String> PRIVATE_KEY_SUFFIXES = List.of(
            ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore");

    /**
     * 判断 Workspace-relative 路径是否必须拒绝。
     *
     * @param relativePath 已 normalize 的相对路径
     * @return 命中固定敏感规则时为 {@code true}
     */
    public boolean isSensitive(Path relativePath) {
        if (relativePath.getNameCount() == 0) {
            return false;
        }
        String protocol = protocolPath(relativePath).toLowerCase(Locale.ROOT);
        String fileName = relativePath.getFileName().toString().toLowerCase(Locale.ROOT);

        if (protocol.equals(".git") || protocol.startsWith(".git/")) {
            return true;
        }
        if (protocol.equals("config/provider.local.properties")) {
            return true;
        }
        if (isSafeTemplate(fileName, protocol)) {
            return false;
        }
        if (fileName.equals(".env") || fileName.startsWith(".env.")) {
            return true;
        }
        if (fileName.equals("id_rsa")
                || fileName.equals("id_dsa")
                || fileName.equals("id_ecdsa")
                || fileName.equals("id_ed25519")) {
            return true;
        }
        if (PRIVATE_KEY_SUFFIXES.stream().anyMatch(fileName::endsWith)) {
            return true;
        }
        return fileName.contains("credential")
                || fileName.contains("secret")
                || containsDelimitedToken(fileName);
    }

    private static boolean containsDelimitedToken(String fileName) {
        String[] parts = fileName.split("[._-]+");
        for (String part : parts) {
            if (part.equals("token") || part.equals("tokens")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSafeTemplate(String fileName, String protocol) {
        return fileName.equals(".env.example")
                || fileName.equals(".env.sample")
                || fileName.equals(".env.template")
                || protocol.equals("config/provider.local.properties.example");
    }

    static String protocolPath(Path relativePath) {
        return relativePath.toString().replace('\\', '/');
    }
}
