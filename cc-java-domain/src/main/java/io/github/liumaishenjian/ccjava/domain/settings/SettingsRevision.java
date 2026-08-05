package io.github.liumaishenjian.ccjava.domain.settings;

import java.util.Objects;

/**
 * 来源字节快照的非敏感版本标识。
 *
 * @param value 小写十六进制 SHA-256 摘要
 * @since 0.8.0
 */
public record SettingsRevision(String value) {
    /** 验证摘要格式，不接受来源正文或路径。 */
    public SettingsRevision {
        value = Objects.requireNonNull(value, "value 不能为空");
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("revision 必须为 SHA-256 摘要");
    }
}
