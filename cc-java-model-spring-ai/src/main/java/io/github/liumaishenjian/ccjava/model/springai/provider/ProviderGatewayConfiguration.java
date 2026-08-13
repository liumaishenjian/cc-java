package io.github.liumaishenjian.ccjava.model.springai.provider;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Spring AI Provider 工厂接收的短生命周期、已验证连接输入。
 *
 * <p>该值仅在单次 route 装配期间存在；认证值不得进入日志、事件或持久化面。工厂不得保留调用方
 * 提供的 {@code char[]}，但第三方 SDK 必然创建的 {@link String} 副本只能通过关闭整个 run route
 * 缩短生命周期。</p>
 *
 * @param providerId 已验证 Provider identity
 * @param baseUri 已验证服务根 URI
 * @param modelId 精确模型 identity
 * @param staticHeaders 非认证静态 Header
 * @param requestTimeout Provider 请求上限
 * @param apiKey 调用方拥有并负责清零的认证字符
 */
public record ProviderGatewayConfiguration(
        String providerId, URI baseUri, String modelId, Map<String, String> staticHeaders,
        Duration requestTimeout, char[] apiKey) {
    /** 防御性复制非秘密集合；secret 数组所有权仍属于调用方。 */
    public ProviderGatewayConfiguration {
        providerId = requireText(providerId, "providerId");
        baseUri = Objects.requireNonNull(baseUri, "baseUri 不能为空");
        modelId = requireText(modelId, "modelId");
        staticHeaders = Map.copyOf(Objects.requireNonNull(staticHeaders, "staticHeaders 不能为空"));
        requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout 不能为空");
        apiKey = Objects.requireNonNull(apiKey, "apiKey 不能为空");
        if (apiKey.length == 0) throw new IllegalArgumentException("apiKey 不能为空");
    }
    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank()) throw new IllegalArgumentException(field + " 不能为空白");
        return value;
    }
    @Override public String toString() {
        return "ProviderGatewayConfiguration[providerId=" + providerId
                + ", baseUri=<redacted>, modelId=" + modelId + ", staticHeaders=<redacted>"
                + ", requestTimeout=" + requestTimeout + ", apiKey=<redacted>]";
    }
}
