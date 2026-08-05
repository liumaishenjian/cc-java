package io.github.liumaishenjian.ccjava.domain.settings;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 单个已完整验证 Settings v1 来源声明的不可变值模型。
 *
 * <p>它不执行跨来源合并，也不持有凭证、端点、路径或 JSON 树；规则和 Tool 配置保持为待后续受信
 * 映射使用的有界声明数据。</p>
 *
 * @param modelName 可选模型名
 * @param permissionMode 可选权限模式名
 * @param permissionRules 有序规则声明
 * @param enabledTools 可选内置 Tool 可见列表
 * @param toolConfigurations 按 Tool 名称整体替换的递归冻结标量配置
 * @param compactInstructions 有序锚点
 * @param diagnosticsVerbosity 可选诊断级别
 * @since 0.8.0
 */
public record DeclaredSettings(Optional<String> modelName, Optional<String> permissionMode,
                               List<DeclaredPermissionRule> permissionRules, Optional<List<String>> enabledTools,
                               Map<String, JsonObject> toolConfigurations, List<String> compactInstructions,
                               Optional<String> diagnosticsVerbosity) {
    /** 递归冻结集合和映射，拒绝空的必需组件。 */
    public DeclaredSettings {
        modelName = Objects.requireNonNull(modelName, "modelName 不能为空");
        permissionMode = Objects.requireNonNull(permissionMode, "permissionMode 不能为空");
        permissionRules = List.copyOf(Objects.requireNonNull(permissionRules, "permissionRules 不能为空"));
        enabledTools = Objects.requireNonNull(enabledTools, "enabledTools 不能为空").map(List::copyOf);
        toolConfigurations = immutableConfigurations(toolConfigurations);
        compactInstructions = List.copyOf(Objects.requireNonNull(compactInstructions, "compactInstructions 不能为空"));
        diagnosticsVerbosity = Objects.requireNonNull(diagnosticsVerbosity, "diagnosticsVerbosity 不能为空");
    }

    @Override
    public String toString() {
        return "DeclaredSettings[modelName=<redacted>, permissionMode=<redacted>, permissionRules=<redacted>, "
                + "enabledTools=<redacted>, toolConfigurations=<redacted>, compactInstructions=<redacted>, "
                + "diagnosticsVerbosity=<redacted>]";
    }

    private static Map<String, JsonObject> immutableConfigurations(Map<String, JsonObject> source) {
        source = Objects.requireNonNull(source, "toolConfigurations 不能为空");
        LinkedHashMap<String, JsonObject> copy = new LinkedHashMap<>();
        source.forEach((tool, configuration) -> {
            if (tool == null || tool.isBlank()) {
                throw new IllegalArgumentException("toolConfigurations 的 Tool 名不能为空");
            }
            copy.put(tool, Objects.requireNonNull(configuration, "tool configuration 不能为空"));
        });
        return Collections.unmodifiableMap(copy);
    }
}
