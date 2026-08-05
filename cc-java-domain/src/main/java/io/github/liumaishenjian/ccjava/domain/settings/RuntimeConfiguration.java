package io.github.liumaishenjian.ccjava.domain.settings;

import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.PermissionRule;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 下一次 Agent Run 可原子替换的框架无关 Runtime 设置投影。
 *
 * <p>该值只保存已经由可信 Application 边界验证的模型选择、S05 规则、内置 Tool 可见性、
 * 非敏感 Tool 配置和 Context/诊断输入。它不保存 Provider 凭证或端点，也不改变 Tool 的
 * Effect、来源、Workspace、安全限制、审批生命周期或 Session Grant。</p>
 *
 * @param modelName 已验证且被当前 Provider 支持的模型名
 * @param permissionMode 当前 S05 默认模式
 * @param permissionRules 以既有 S05 固定优先级求值的启动规则
 * @param enabledBuiltinTools 按 Registry 注册顺序保留的内置 Tool 名称
 * @param toolConfigurations 经可信 schema 验证的非敏感 Tool 配置
 * @param compactAnchors 作为 Context 输入的有序保留锚点
 * @param diagnosticsVerbosity 已脱敏诊断投影的详细程度
 * @since 0.8.0
 */
public record RuntimeConfiguration(
        Optional<String> modelName,
        PermissionMode permissionMode,
        List<PermissionRule> permissionRules,
        List<String> enabledBuiltinTools,
        Map<String, io.github.liumaishenjian.ccjava.domain.JsonObject> toolConfigurations,
        List<String> compactAnchors,
        RuntimeDiagnosticsVerbosity diagnosticsVerbosity) {

    /** 递归冻结所有集合，避免配置发布后被外部引用修改。 */
    public RuntimeConfiguration {
        modelName = Objects.requireNonNull(modelName, "modelName 不能为空").map(RuntimeConfiguration::requireModelName);
        permissionMode = Objects.requireNonNull(permissionMode, "permissionMode 不能为空");
        permissionRules = List.copyOf(Objects.requireNonNull(permissionRules, "permissionRules 不能为空"));
        enabledBuiltinTools = List.copyOf(Objects.requireNonNull(enabledBuiltinTools, "enabledBuiltinTools 不能为空"));
        toolConfigurations = freezeConfigurations(toolConfigurations);
        compactAnchors = List.copyOf(Objects.requireNonNull(compactAnchors, "compactAnchors 不能为空"));
        diagnosticsVerbosity = Objects.requireNonNull(diagnosticsVerbosity, "diagnosticsVerbosity 不能为空");
    }

    /**
     * 返回不含 Settings 覆盖的保守初始投影。
     *
     * @param permissionMode 已装配的权限模式
     * @param enabledBuiltinTools 当前可见的内置 Tool
     * @return 可作为首次原子应用旧值的不可变配置
     */
    public static RuntimeConfiguration initial(PermissionMode permissionMode, List<String> enabledBuiltinTools) {
        return new RuntimeConfiguration(Optional.empty(), permissionMode, List.of(), enabledBuiltinTools,
                Map.of(), List.of(), RuntimeDiagnosticsVerbosity.SUMMARY);
    }

    @Override
    public String toString() {
        return "RuntimeConfiguration[modelName=<redacted>, permissionMode=" + permissionMode
                + ", permissionRules=<redacted>, enabledBuiltinTools=" + enabledBuiltinTools
                + ", toolConfigurations=<redacted>, compactAnchors=<redacted>, diagnosticsVerbosity="
                + diagnosticsVerbosity + "]";
    }

    private static Map<String, io.github.liumaishenjian.ccjava.domain.JsonObject> freezeConfigurations(
            Map<String, io.github.liumaishenjian.ccjava.domain.JsonObject> source) {
        source = Objects.requireNonNull(source, "toolConfigurations 不能为空");
        LinkedHashMap<String, io.github.liumaishenjian.ccjava.domain.JsonObject> copy = new LinkedHashMap<>();
        source.forEach((name, configuration) -> copy.put(requireToolName(name),
                Objects.requireNonNull(configuration, "tool configuration 不能为空")));
        return java.util.Collections.unmodifiableMap(copy);
    }

    private static String requireModelName(String value) {
        value = Objects.requireNonNull(value, "modelName value 不能为空");
        if (value.isBlank() || value.codePointCount(0, value.length()) > 256
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("modelName 非法");
        }
        return value;
    }

    private static String requireToolName(String value) {
        value = Objects.requireNonNull(value, "tool name 不能为空");
        if (value.isBlank() || value.codePointCount(0, value.length()) > 128
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("tool name 非法");
        }
        return value;
    }
}
