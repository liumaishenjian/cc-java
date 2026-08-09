package io.github.liumaishenjian.ccjava.core.plugin;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginComponentDescriptor;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;
import java.util.Objects;

/**
 * 宿主传给受控 Provider factory 的已验证描述，不包含任意 Java 类型或可执行入口。
 *
 * @param snapshot 可信且持有 lease 的 Plugin snapshot
 * @param component manifest 中的 {@code mcp-backed} provider 组件
 * @since 0.11.0
 */
public record PluginToolProviderDescriptor(
        PluginSnapshot snapshot,
        PluginComponentDescriptor component) {

    public PluginToolProviderDescriptor {
        snapshot = Objects.requireNonNull(snapshot, "snapshot 不能为空");
        component = Objects.requireNonNull(component, "component 不能为空");
        if (!"mcp-backed".equals(component.providerType())) {
            throw new IllegalArgumentException("仅支持 mcp-backed Provider");
        }
        if (!snapshot.manifest().components().contains(component)) {
            throw new IllegalArgumentException("Provider 不属于固定 manifest");
        }
    }
}
