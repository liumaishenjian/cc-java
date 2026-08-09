package io.github.liumaishenjian.ccjava.domain.plugin;

import java.util.Objects;

/**
 * Plugin 内容身份与 Trust 输入，不是签名、作者身份、无恶意证明或 Sandbox。
 *
 * @param pluginId Plugin 身份
 * @param version manifest 版本
 * @param treeDigest canonical tree SHA-256
 * @param manifestDigest plugin.json SHA-256
 * @since 0.11.0
 */
public record PluginFingerprint(PluginId pluginId, String version, String treeDigest, String manifestDigest) {
    /** 校验 fingerprint 的精确内容身份。 */
    public PluginFingerprint {
        pluginId = Objects.requireNonNull(pluginId, "pluginId 不能为空");
        version = Objects.requireNonNull(version, "version 不能为空");
        treeDigest = digest(treeDigest, "treeDigest");
        manifestDigest = digest(manifestDigest, "manifestDigest");
    }
    private static String digest(String value, String field) {
        value = Objects.requireNonNull(value, field + " 不能为空");
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field + " 必须是 SHA-256");
        return value;
    }
}
