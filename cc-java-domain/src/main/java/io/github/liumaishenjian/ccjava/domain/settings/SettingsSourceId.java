package io.github.liumaishenjian.ccjava.domain.settings;

import java.util.Objects;

/**
 * 不含物理路径、凭证或 Settings 正文的来源标识。
 *
 * @param kind 来源类别
 * @param safeId 用于诊断的安全逻辑标识
 * @since 0.8.0
 */
public record SettingsSourceId(SettingsSourceKind kind, String safeId) {
    /**
     * 创建可投影到诊断和 provenance 的来源标识。
     *
     * @param kind 固定来源类别
     * @param safeId 不含路径分隔符与控制字符的逻辑标识
     */
    public SettingsSourceId {
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        safeId = Objects.requireNonNull(safeId, "safeId 不能为空");
        if (safeId.isBlank() || safeId.codePointCount(0, safeId.length()) > 128
                || safeId.indexOf('\\') >= 0 || safeId.indexOf('/') >= 0
                || safeId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("safeId 非法");
        }
    }
}
