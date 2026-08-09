package io.github.liumaishenjian.ccjava.domain.plugin;

import java.util.List;
import java.util.Objects;

/**
 * strict manifest 解析后的组件描述；不携带 Java 类、ToolDefinition、ToolSource 或可执行脚本。
 *
 * @param kind 组件类型
 * @param name 组件内规范名称
 * @param logicalPath plugin-root-relative 逻辑路径
 * @param providerType Tool Provider 的宿主预注册类型；仅 TOOL_PROVIDER 使用
 * @param references 同 Plugin 内 named component 引用；仅 TOOL_PROVIDER 使用
 * @param configDigest 已验证配置摘要；仅 TOOL_PROVIDER 使用
 * @since 0.11.0
 */
public record PluginComponentDescriptor(PluginComponentKind kind, String name, String logicalPath,
        String providerType, List<String> references, String configDigest) {
    /** 校验封闭 schema 与各组件字段组合。 */
    public PluginComponentDescriptor {
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        name = requireName(name, "name");
        logicalPath = requireLogicalPath(logicalPath);
        references = List.copyOf(references == null ? List.of() : references);
        if (references.stream().anyMatch(value -> !requireName(value, "reference").equals(value))
                || references.stream().distinct().count() != references.size()) {
            throw new IllegalArgumentException("组件引用非法或重复");
        }
        if (kind == PluginComponentKind.TOOL_PROVIDER) {
            providerType = requireName(providerType, "providerType");
            if (!"mcp-backed".equals(providerType) || references.isEmpty()
                    || configDigest == null || !configDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("S11 Tool Provider 只允许完整 mcp-backed descriptor");
            }
        } else if (providerType != null || !references.isEmpty() || configDigest != null) {
            throw new IllegalArgumentException("非 Tool Provider 不得携带 Provider 字段");
        }
    }

    static String requireName(String value, String field) {
        value = Objects.requireNonNull(value, field + " 不能为空");
        if (value.length() > 64 || !value.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException(field + " 必须是 ASCII kebab-case");
        }
        return value;
    }

    static String requireLogicalPath(String value) {
        value = Objects.requireNonNull(value, "logicalPath 不能为空");
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (value.isBlank() || value.startsWith("/") || value.startsWith("\\") || value.contains("\\")
                || value.contains(":") || value.codePoints().anyMatch(codePoint -> codePoint == 0)
                || java.util.Arrays.asList(value.split("/", -1)).contains("..")
                || lower.endsWith(".jar") || lower.endsWith(".class")
                || lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib")
                || lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".cmd")
                || lower.endsWith(".ps1") || lower.endsWith(".sh")) {
            throw new IllegalArgumentException("logicalPath 必须是安全相对逻辑路径");
        }
        return value;
    }
}
