package io.github.liumaishenjian.ccjava.model.springai.config;

import java.net.URI;
import java.util.Objects;

/**
 * Git 忽略配置或环境提供的 Anthropic Provider 设置。
 *
 * <p>{@code toString()} 不包含 API Key。</p>
 *
 * @param baseUrl Anthropic-compatible 服务根地址
 * @param apiKey 密钥，仅在 Adapter 创建时使用
 * @param model 模型名
 * @since 0.1.0
 */
public record AnthropicSettings(URI baseUrl, String apiKey, String model) {
    /** 校验地址、密钥存在性和模型 identity。 */
    public AnthropicSettings {
        baseUrl = Objects.requireNonNull(baseUrl, "baseUrl 不能为空");
        if (!"https".equalsIgnoreCase(baseUrl.getScheme())
                && !("http".equalsIgnoreCase(baseUrl.getScheme()) && baseUrl.getHost() != null
                && (baseUrl.getHost().equals("127.0.0.1") || baseUrl.getHost().equals("localhost")))) {
            throw new IllegalArgumentException("Anthropic Base URL 必须为 HTTPS 或 loopback HTTP");
        }
        apiKey = requireText(apiKey, "apiKey", 4096);
        model = requireText(model, "model", 256);
    }
    private static String requireText(String value, String field, int max) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank() || value.length() > max) throw new IllegalArgumentException(field + " 非法");
        return value;
    }
    @Override public String toString() {
        return "AnthropicSettings[baseUrl=<redacted>, apiKey=<redacted>, model=" + model + "]";
    }
}
