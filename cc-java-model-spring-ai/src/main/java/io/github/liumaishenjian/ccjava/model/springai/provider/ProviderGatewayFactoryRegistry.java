package io.github.liumaishenjian.ccjava.model.springai.provider;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 编译期封闭、无动态 discovery 的 Provider Gateway factory registry。 */
public final class ProviderGatewayFactoryRegistry {
    private final Map<ProviderGatewayKind, ProviderGatewayFactory> factories;
    /**
     * 注册全部受支持 factory；重复或缺失 kind 均 fail closed。
     *
     * @param values 必须覆盖全部协议种类且不得重复的 Provider 工厂
     */
    public ProviderGatewayFactoryRegistry(List<ProviderGatewayFactory> values) {
        EnumMap<ProviderGatewayKind, ProviderGatewayFactory> result = new EnumMap<>(ProviderGatewayKind.class);
        for (ProviderGatewayFactory value : List.copyOf(Objects.requireNonNull(values, "values 不能为空"))) {
            if (result.putIfAbsent(value.kind(), value) != null) throw new IllegalArgumentException("Provider factory 重复");
        }
        if (result.size() != ProviderGatewayKind.values().length) throw new IllegalArgumentException("Provider factory 不完整");
        factories = Map.copyOf(result);
    }
    /**
     * 创建包含 custom compatible、Anthropic、OpenRouter 的生产 registry。
     *
     * @return 完整覆盖编译期协议种类的生产工厂注册表
     */
    public static ProviderGatewayFactoryRegistry production() {
        return new ProviderGatewayFactoryRegistry(List.of(new OpenAiCompatibleProviderGatewayFactory(),
                new AnthropicProviderGatewayFactory(), new OpenRouterProviderGatewayFactory()));
    }
    /**
     * 精确返回 kind 对应工厂，不做协议 fallback。
     *
     * @param kind 待解析的编译期 Provider Gateway 协议种类
     * @return 与种类精确匹配的 Provider Gateway 工厂
     */
    public ProviderGatewayFactory require(ProviderGatewayKind kind) {
        ProviderGatewayFactory value = factories.get(Objects.requireNonNull(kind, "kind 不能为空"));
        if (value == null) throw new IllegalArgumentException("Provider factory 不存在");
        return value;
    }
}
