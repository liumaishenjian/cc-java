package io.github.liumaishenjian.ccjava.domain.plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Plugin manifest v1 的不可变领域协议。
 *
 * @param schemaVersion 固定为 1
 * @param id Plugin 身份
 * @param version 有界版本文本，不承诺 SemVer
 * @param description 可选单行描述
 * @param requiresHost 可选宿主版本约束文本；S11 只保留诊断，不做迁移承诺
 * @param components 最多 128 个严格组件
 * @since 0.11.0
 */
public record PluginManifest(int schemaVersion, PluginId id, String version, String description,
        String requiresHost, List<PluginComponentDescriptor> components) {
    /** 校验 manifest 上限、唯一名称、namespace 与 named MCP 引用。 */
    public PluginManifest {
        if (schemaVersion != 1) throw new IllegalArgumentException("schemaVersion 必须为 1");
        id = Objects.requireNonNull(id, "id 不能为空");
        version = requireLine(version, 64, "version", false);
        description = requireLine(description, 512, "description", true);
        requiresHost = requireLine(requiresHost, 64, "requiresHost", true);
        components = List.copyOf(Objects.requireNonNull(components, "components 不能为空"));
        if (components.isEmpty() || components.size() > 128) {
            throw new IllegalArgumentException("components 必须为 1～128 项");
        }
        Set<String> names = new HashSet<>();
        Set<String> namespaces = new HashSet<>();
        for (PluginComponentDescriptor component : components) {
            String local = component.kind() + "\0" + component.name();
            if (!names.add(local) || !namespaces.add(PluginNamespace.qualified(id, component.kind(), component.name()))) {
                throw new IllegalArgumentException("组件名称或 namespace 冲突");
            }
        }
        Set<String> servers = new HashSet<>();
        components.stream().filter(component -> component.kind() == PluginComponentKind.MCP_SERVER)
                .forEach(component -> servers.add(component.name()));
        for (PluginComponentDescriptor provider : components.stream()
                .filter(component -> component.kind() == PluginComponentKind.TOOL_PROVIDER).toList()) {
            if (!servers.containsAll(provider.references())) {
                throw new IllegalArgumentException("Tool Provider 引用了未声明 MCP Server");
            }
        }
    }

    private static String requireLine(String value, int max, String field, boolean optional) {
        if (value == null && optional) return null;
        value = Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank() || value.codePointCount(0, value.length()) > max
                || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException(field + " 必须是有界单行文本");
        }
        return value;
    }
}
