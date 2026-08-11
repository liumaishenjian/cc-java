package io.github.liumaishenjian.ccjava.domain.plugin;

import java.util.Objects;

/** 生成不依赖目录或注册顺序的 Plugin 全局名称。 @since 0.11.0 */
public final class PluginNamespace {
    private PluginNamespace() {}

    /**
     * 为 Plugin 组件生成稳定且全局唯一的名称。
     *
     * @param pluginId Plugin 身份
     * @param kind 组件类型
     * @param componentName 组件规范名称
     * @return {@code plugin__<id>__<kind>__<component>}
     */
    public static String qualified(PluginId pluginId, PluginComponentKind kind, String componentName) {
        return "plugin__" + Objects.requireNonNull(pluginId, "pluginId 不能为空").value()
                + "__" + Objects.requireNonNull(kind, "kind 不能为空").namespaceSegment()
                + "__" + PluginComponentDescriptor.requireName(componentName, "componentName");
    }

    /**
     * 为 Provider 下远端 Tool 生成完整名称，Session Grant 必须绑定此完整值。
     *
     * @param providerQualifiedName Provider 全局名称
     * @param remoteToolName 远端规范 Tool 名
     * @return 完整 Tool 名
     */
    public static String qualifiedTool(String providerQualifiedName, String remoteToolName) {
        String provider = Objects.requireNonNull(providerQualifiedName, "providerQualifiedName 不能为空");
        if (!provider.startsWith("plugin__") || provider.length() > 220) {
            throw new IllegalArgumentException("providerQualifiedName 非法");
        }
        return provider + "__" + PluginComponentDescriptor.requireName(remoteToolName, "remoteToolName");
    }
}
