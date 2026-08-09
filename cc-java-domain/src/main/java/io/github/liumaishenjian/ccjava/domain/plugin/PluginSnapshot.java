package io.github.liumaishenjian.ccjava.domain.plugin;

import java.util.Objects;

/**
 * Session 可持有的不可变 Plugin 快照；{@code safeContentId} 是 Adapter 逻辑句柄而非绝对路径。
 *
 * @param manifest strict manifest
 * @param fingerprint 精确内容身份
 * @param safeContentId 不含物理路径的 content-addressed 逻辑 ID
 * @since 0.11.0
 */
public record PluginSnapshot(PluginManifest manifest, PluginFingerprint fingerprint, String safeContentId) {
    /** 校验 manifest 与 fingerprint 属于相同 Plugin/version。 */
    public PluginSnapshot {
        manifest = Objects.requireNonNull(manifest, "manifest 不能为空");
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint 不能为空");
        if (!manifest.id().equals(fingerprint.pluginId()) || !manifest.version().equals(fingerprint.version())) {
            throw new IllegalArgumentException("manifest 与 fingerprint 不匹配");
        }
        safeContentId = Objects.requireNonNull(safeContentId, "safeContentId 不能为空");
        if (!safeContentId.matches("[a-z0-9-]{1,64}")) throw new IllegalArgumentException("safeContentId 非法");
    }
}
