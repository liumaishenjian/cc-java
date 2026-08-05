package io.github.liumaishenjian.ccjava.domain.settings;

import java.util.Objects;

/**
 * 一个最终 Settings 值及其最后有效来源。
 *
 * <p>该类型仅用于内部有效设置投影；{@link #toString()} 不回显可能来自不可信 Settings 的值。</p>
 *
 * @param value 已解析的最终值
 * @param provenance 产生该值的来源操作
 * @param <T> 值类型
 * @since 0.8.0
 */
public record ProvenancedSettingValue<T>(T value, SettingProvenance provenance) {
    /** 创建不可为空的最终值投影。 */
    public ProvenancedSettingValue {
        value = Objects.requireNonNull(value, "value 不能为空");
        provenance = Objects.requireNonNull(provenance, "provenance 不能为空");
    }

    @Override
    public String toString() {
        return "ProvenancedSettingValue[value=<redacted>, provenance=" + provenance + "]";
    }
}
