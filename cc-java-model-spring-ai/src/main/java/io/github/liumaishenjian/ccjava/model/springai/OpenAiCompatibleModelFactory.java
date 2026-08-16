package io.github.liumaishenjian.ccjava.model.springai;

import io.github.liumaishenjian.ccjava.model.springai.config.OpenAiCompatibleSettings;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import io.micrometer.observation.ObservationRegistry;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * 根据项目本地配置创建 Spring AI 2.0 OpenAI ChatModel。
 *
 * <p>Factory 不使用 Spring Boot 自动配置，不创建 ChatClient，也不注册
 * ToolCallingAdvisor。直接使用 ChatModel 能让原始 Tool Call 返回 Core，
 * Tool 执行权仍属于项目的 ToolExecutionPipeline。</p>
 *
 * @since 0.1.0
 */
public final class OpenAiCompatibleModelFactory {

    /**
     * 创建无状态 Provider Factory。
     */
    public OpenAiCompatibleModelFactory() {
    }

    /**
     * 创建支持同步和流式调用的 OpenAI-compatible ChatModel。
     *
     * @param settings 已校验且字符串表示脱敏的本地设置
     * @return Spring AI OpenAI ChatModel
     */
    public ChatModel create(OpenAiCompatibleSettings settings) {
        return createResource(settings, java.util.Map.of(), Duration.ofMinutes(30)).chatModel();
    }

    /**
     * 使用 definition 指定的非认证 Header 与请求 timeout 创建 ChatModel。
     *
     * @param settings 已校验且字符串表示脱敏的本地设置
     * @param staticHeaders 随请求发送的非认证静态 Header
     * @param requestTimeout 单次 Provider 请求的超时时间
     * @return 仅执行单个模型回合的 Spring AI ChatModel
     */
    public ChatModel create(OpenAiCompatibleSettings settings, java.util.Map<String, String> staticHeaders,
                            Duration requestTimeout) {
        return createResource(settings, staticHeaders, requestTimeout).chatModel();
    }

    /**
     * 创建由调用方显式关闭的模型资源。
     *
     * <p>生产 Composition 必须使用本入口，确保 Spring AI 内部同步与异步 OpenAI Client
     * 都有明确所有者；只需短生命周期测试兼容的调用方可继续使用 {@link #create}。</p>
     *
     * @param settings 已校验且字符串表示脱敏的本地设置
     * @param staticHeaders 随请求发送的非认证静态 Header
     * @param requestTimeout 单次 Provider 请求的超时时间
     * @return 模型及底层 HTTP Client 的关闭资源
     */
    public OpenAiCompatibleModelResource createResource(
            OpenAiCompatibleSettings settings,
            java.util.Map<String, String> staticHeaders,
            Duration requestTimeout) {
        Objects.requireNonNull(settings, "settings 不能为空");
        java.util.Map<String, String> headers = java.util.Map.copyOf(
                Objects.requireNonNull(staticHeaders, "staticHeaders 不能为空"));
        Duration timeout = Objects.requireNonNull(requestTimeout, "requestTimeout 不能为空");
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(chatApiBaseUrl(settings.baseUrl()))
                .apiKey(settings.apiKey())
                .model(settings.model())
                .customHeaders(headers)
                .timeout(timeout)
                .streamUsage(true)
                .maxRetries(0)
                .build();
        String baseUrl = options.getBaseUrl();
        String apiKey = options.getApiKey();
        var syncClient = OpenAiSetup.setupSyncClient(
                baseUrl, apiKey, options.getCredential(), options.getMicrosoftDeploymentName(),
                options.getMicrosoftFoundryServiceVersion(), options.getOrganizationId(),
                options.isMicrosoftFoundry(), options.isGitHubModels(), options.getModel(), timeout, 0,
                options.getProxy(), headers, ObservationRegistry.NOOP, null, java.util.List.of());
        var asyncClient = OpenAiSetup.setupAsyncClient(
                baseUrl, apiKey, options.getCredential(), options.getMicrosoftDeploymentName(),
                options.getMicrosoftFoundryServiceVersion(), options.getOrganizationId(),
                options.isMicrosoftFoundry(), options.isGitHubModels(), options.getModel(), timeout, 0,
                options.getProxy(), headers, ObservationRegistry.NOOP, null, java.util.List.of());
        try {
            ChatModel model = OpenAiChatModel.builder()
                    .openAiClient(syncClient)
                    .openAiClientAsync(asyncClient)
                    .options(options)
                    .build();
            return new OpenAiCompatibleModelResource(model, syncClient, asyncClient);
        } catch (RuntimeException | Error failure) {
            try {
                asyncClient.close();
            } finally {
                syncClient.close();
            }
            throw failure;
        }
    }

    /**
     * 把维护者填写的服务根地址规范化为 OpenAI Java SDK 所需的 API Base URL。
     *
     * <p>若路径已经以 {@code /v1} 结束则保持不变，否则追加 {@code /v1}。
     * 该规则与 S02 连通性 Smoke 使用的请求路径一致。</p>
     *
     * @param configuredBaseUrl 本地配置中的绝对 URI
     * @return 不以斜杠结尾的 API Base URL
     */
    static String chatApiBaseUrl(URI configuredBaseUrl) {
        String base = Objects.requireNonNull(configuredBaseUrl, "configuredBaseUrl 不能为空")
                .toString()
                .replaceAll("/+$", "");
        return base.endsWith("/v1") ? base : base + "/v1";
    }
}
