package io.github.liumaishenjian.ccjava.domain.settings;

import java.util.Objects;

/**
 * Tool 配置的最终整体替换值或显式删除 tombstone。
 *
 * <p>Tool config 不支持递归成员合并；删除保留 provenance 以供安全 doctor 投影使用。</p>
 *
 * @param declaration 最后出现的整体替换或删除声明
 * @param provenance 产生该最终操作的来源
 * @since 0.8.0
 */
public record EffectiveToolConfiguration(DeclaredToolConfiguration declaration, SettingProvenance provenance) {
    /** 创建不可为空的 Tool 配置最终操作。 */
    public EffectiveToolConfiguration {
        declaration = Objects.requireNonNull(declaration, "declaration 不能为空");
        provenance = Objects.requireNonNull(provenance, "provenance 不能为空");
    }

    @Override
    public String toString() {
        return "EffectiveToolConfiguration[declaration=<redacted>, provenance=" + provenance + "]";
    }
}
