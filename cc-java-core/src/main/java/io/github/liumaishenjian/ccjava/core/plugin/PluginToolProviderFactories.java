package io.github.liumaishenjian.ccjava.core.plugin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 只接受宿主代码提供 factory 的不可变 Provider registry。
 *
 * <p>Plugin manifest 的字符串只能查找这里已经注册的实现，不能动态加载 JVM/native 代码。</p>
 *
 * @since 0.11.0
 */
public final class PluginToolProviderFactories {

    private final Map<String, PluginToolProviderFactory> factories;

    /**
     * 冻结宿主预注册 factory，并拒绝非法或重复 provider type。
     *
     * @param factories 宿主代码提供的受控 factories
     */
    public PluginToolProviderFactories(Collection<? extends PluginToolProviderFactory> factories) {
        Objects.requireNonNull(factories, "factories 不能为空");
        var registered = new LinkedHashMap<String, PluginToolProviderFactory>();
        for (PluginToolProviderFactory factory : factories) {
            factory = Objects.requireNonNull(factory, "factory 不能为空");
            String type = Objects.requireNonNull(factory.providerType(), "providerType 不能为空");
            if (!type.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
                throw new IllegalArgumentException("providerType 非法");
            }
            if (registered.putIfAbsent(type, factory) != null) {
                throw new IllegalArgumentException("重复 Plugin provider type");
            }
        }
        this.factories = Map.copyOf(registered);
    }

    /**
     * 按 manifest provider type 查找宿主实现。
     *
     * @param providerType manifest 声明的稳定类型
     * @return 宿主未注册该类型时为空
     */
    public Optional<PluginToolProviderFactory> find(String providerType) {
        return Optional.ofNullable(factories.get(Objects.requireNonNull(providerType, "providerType 不能为空")));
    }
}
