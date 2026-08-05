package io.github.liumaishenjian.ccjava.domain.settings;

import java.util.Objects;

/**
 * 合并完成但尚未映射到 S05 Policy 的权限规则及来源。
 *
 * <p>规则定义保持完整对象替换语义；选择器不会从 {@link #toString()} 或诊断投影泄漏。</p>
 *
 * @param definition 最后有效的完整规则定义
 * @param provenance 最后设置或替换规则的来源
 * @since 0.8.0
 */
public record EffectivePermissionRule(DeclaredPermissionRuleDefinition definition, SettingProvenance provenance) {
    /** 创建不可为空的最终规则投影。 */
    public EffectivePermissionRule {
        definition = Objects.requireNonNull(definition, "definition 不能为空");
        provenance = Objects.requireNonNull(provenance, "provenance 不能为空");
    }

    @Override
    public String toString() {
        return "EffectivePermissionRule[definition=<redacted>, provenance=" + provenance + "]";
    }
}
