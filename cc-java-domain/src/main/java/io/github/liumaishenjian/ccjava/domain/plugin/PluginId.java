package io.github.liumaishenjian.ccjava.domain.plugin;

import java.util.Objects;

/** Plugin 的规范 ASCII kebab-case 身份，不包含路径语义。 @param value 身份值 @since 0.11.0 */
public record PluginId(String value) implements Comparable<PluginId> {
    /** 校验 1～64 字符的规范身份。 */
    public PluginId {
        value = Objects.requireNonNull(value, "value 不能为空");
        if (value.length() > 64 || !value.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("Plugin ID 必须是最长 64 字符的 ASCII kebab-case");
        }
    }
    @Override public int compareTo(PluginId other) { return value.compareTo(other.value); }
}
