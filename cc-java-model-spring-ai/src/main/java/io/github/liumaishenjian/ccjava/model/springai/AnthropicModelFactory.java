package io.github.liumaishenjian.ccjava.model.springai;

import io.github.liumaishenjian.ccjava.model.springai.config.AnthropicSettings;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Duration;
import java.util.Objects;

/**
 * 创建 Spring AI 2.0 Anthropic Adapter 所需 ChatModel。
 *
 * <p>SDK 内建重试关闭，自动 Tool Loop 不注册；取消、重试、Fallback 与 Tool 执行仍由
 * cc-java Runtime 管理。当前第三方 SDK 的 HTTP 创建不经过项目 NetworkAccessPort，因而
 * 只能报告应用层配置受控，不能冒充 OS/JVM 强制网络边界。</p>
 *
 * @since 0.1.0
 */
public final class AnthropicModelFactory {
    /** 创建无状态 Provider factory。 */
    public AnthropicModelFactory() {
    }

    /**
     * 创建关闭 SDK 自动重试与 Tool Loop 的 Anthropic ChatModel。
     *
     * @param settings 已校验且不进入日志的 Provider 配置
     * @return 仅执行单个模型回合的 Spring AI ChatModel
     */
    public ChatModel create(AnthropicSettings settings) {
        return create(settings, java.util.Map.of(), Duration.ofMinutes(30));
    }

    /**
     * 使用 definition 的非认证 Header 与请求 timeout 创建禁用自动重试的 ChatModel。
     *
     * @param settings 已校验且不进入日志的 Provider 配置
     * @param staticHeaders 随请求发送的非认证静态 Header
     * @param requestTimeout 单次 Provider 请求的超时时间
     * @return 仅执行单个模型回合的 Spring AI ChatModel
     */
    public ChatModel create(AnthropicSettings settings, java.util.Map<String, String> staticHeaders,
                            Duration requestTimeout) {
        Objects.requireNonNull(settings, "settings 不能为空");
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .baseUrl(settings.baseUrl().toString().replaceAll("/+$", ""))
                .apiKey(settings.apiKey())
                .model(settings.model())
                .customHeaders(java.util.Map.copyOf(Objects.requireNonNull(staticHeaders, "staticHeaders 不能为空")))
                .timeout(Objects.requireNonNull(requestTimeout, "requestTimeout 不能为空"))
                .maxRetries(0)
                .build();
        return AnthropicChatModel.builder().options(options).build();
    }
}
