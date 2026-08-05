package io.github.liumaishenjian.ccjava.domain.settings;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 全部有效 Settings 来源合并后的不可变、隐私安全投影。
 *
 * <p>本类型不读取文件、不解析 JSON、不应用 S05 Policy，也不发布 last-known-good 快照。每个最终
 * 字段、Tool 配置、anchor、规则和删除 tombstone 都保留可安全投影的 provenance。</p>
 *
 * @param modelName 最终模型名及来源
 * @param permissionMode 最终权限模式及来源
 * @param permissionRules 按首次 ruleId 位置稳定排序的最终规则
 * @param enabledTools 最终 Tool 可见列表及各项来源
 * @param toolConfigurations Tool 配置替换或删除 tombstone
 * @param compactInstructions 有序去重后的 anchor 及全部抑制证据
 * @param removedPermissionRules 已删除规则的 ruleId 到 tombstone provenance
 * @param diagnosticsVerbosity 最终诊断详细程度及来源
 * @param diagnostics 合并期间产生的无正文诊断
 * @since 0.8.0
 */
public record EffectiveSettings(
        Optional<ProvenancedSettingValue<String>> modelName,
        Optional<ProvenancedSettingValue<String>> permissionMode,
        List<EffectivePermissionRule> permissionRules,
        Optional<ProvenancedSettingValue<List<ProvenancedSettingValue<String>>>> enabledTools,
        Map<String, EffectiveToolConfiguration> toolConfigurations,
        List<EffectiveCompactInstruction> compactInstructions,
        Map<String, SettingProvenance> removedPermissionRules,
        Optional<ProvenancedSettingValue<String>> diagnosticsVerbosity,
        List<ConfigurationDiagnostic> diagnostics) {
    /** 创建递归冻结的最终投影。 */
    public EffectiveSettings {
        modelName = Objects.requireNonNull(modelName, "modelName 不能为空");
        permissionMode = Objects.requireNonNull(permissionMode, "permissionMode 不能为空");
        permissionRules = List.copyOf(Objects.requireNonNull(permissionRules, "permissionRules 不能为空"));
        enabledTools = freezeEnabledTools(enabledTools);
        toolConfigurations = freezeConfigurations(toolConfigurations);
        compactInstructions = List.copyOf(Objects.requireNonNull(compactInstructions, "compactInstructions 不能为空"));
        removedPermissionRules = freezeRemovedRules(removedPermissionRules);
        diagnosticsVerbosity = Objects.requireNonNull(diagnosticsVerbosity, "diagnosticsVerbosity 不能为空");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics 不能为空"));
    }

    @Override
    public String toString() {
        return "EffectiveSettings[modelName=<redacted>, permissionMode=<redacted>, permissionRules=<redacted>, "
                + "enabledTools=<redacted>, toolConfigurations=<redacted>, compactInstructions=<redacted>, "
                + "removedPermissionRules=" + removedPermissionRules + ", diagnosticsVerbosity=<redacted>, diagnostics="
                + diagnostics + "]";
    }

    private static Optional<ProvenancedSettingValue<List<ProvenancedSettingValue<String>>>> freezeEnabledTools(
            Optional<ProvenancedSettingValue<List<ProvenancedSettingValue<String>>>> source) {
        source = Objects.requireNonNull(source, "enabledTools 不能为空");
        return source.map(value -> new ProvenancedSettingValue<>(List.copyOf(value.value()), value.provenance()));
    }

    private static Map<String, EffectiveToolConfiguration> freezeConfigurations(
            Map<String, EffectiveToolConfiguration> source) {
        source = Objects.requireNonNull(source, "toolConfigurations 不能为空");
        LinkedHashMap<String, EffectiveToolConfiguration> copy = new LinkedHashMap<>();
        source.forEach((tool, configuration) -> copy.put(
                Objects.requireNonNull(tool, "tool 名不能为空"),
                Objects.requireNonNull(configuration, "tool configuration 不能为空")));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, SettingProvenance> freezeRemovedRules(Map<String, SettingProvenance> source) {
        source = Objects.requireNonNull(source, "removedPermissionRules 不能为空");
        LinkedHashMap<String, SettingProvenance> copy = new LinkedHashMap<>();
        source.forEach((ruleId, provenance) -> copy.put(
                Objects.requireNonNull(ruleId, "ruleId 不能为空"),
                Objects.requireNonNull(provenance, "rule provenance 不能为空")));
        return Collections.unmodifiableMap(copy);
    }
}
