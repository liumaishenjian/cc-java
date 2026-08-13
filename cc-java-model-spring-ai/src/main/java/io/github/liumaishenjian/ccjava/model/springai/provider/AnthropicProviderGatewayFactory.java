package io.github.liumaishenjian.ccjava.model.springai.provider;

import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.model.springai.AnthropicModelFactory;
import io.github.liumaishenjian.ccjava.model.springai.SpringAiModelGateway;
import io.github.liumaishenjian.ccjava.model.springai.config.AnthropicSettings;
import java.util.Arrays;

/** 使用 Anthropic 官方 Messages adapter 创建单 route Gateway。 */
public final class AnthropicProviderGatewayFactory implements ProviderGatewayFactory {
    /** 创建无状态 Anthropic Gateway 工厂。 */
    public AnthropicProviderGatewayFactory() {
    }

    @Override public ProviderGatewayKind kind() { return ProviderGatewayKind.ANTHROPIC; }
    /** 创建 Anthropic Gateway；secret 只在 SDK 构造边界转换为不可清零字符串。 */
    @Override public ModelGateway create(ProviderGatewayConfiguration configuration) {
        char[] key = configuration.apiKey();
        try {
            AnthropicSettings settings = new AnthropicSettings(
                    configuration.baseUri(), new String(key), configuration.modelId());
            return new SpringAiModelGateway(new AnthropicModelFactory().create(
                    settings, configuration.staticHeaders(), configuration.requestTimeout()), configuration.modelId());
        } finally {
            Arrays.fill(key, '\0');
        }
    }
}
