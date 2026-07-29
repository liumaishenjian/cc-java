package io.github.liumaishenjian.ccjava.model.springai.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

import static io.github.liumaishenjian.ccjava.model.springai.config.ProviderConfigurationException.Code.INVALID_BASE_URL;
import static io.github.liumaishenjian.ccjava.model.springai.config.ProviderConfigurationException.Code.INVALID_MODEL;
import static io.github.liumaishenjian.ccjava.model.springai.config.ProviderConfigurationException.Code.REQUIRED_VALUE_MISSING;

/**
 * OpenAI-compatible Provider 的已校验本地配置。
 *
 * <p>API Key 在内存中仅为后续 Client 装配保留，{@link #toString()} 永远不会输出它。
 * 当前 S02 Spike 允许 HTTP 用于维护者明确选择的本机或内网中转地址；生产化阶段仍需
 * 根据部署边界决定是否强制 HTTPS。</p>
 *
 * @since 0.1.0
 */
public final class OpenAiCompatibleSettings {

    private static final int MAX_MODEL_LENGTH = 200;

    private final URI baseUrl;
    private final String apiKey;
    private final String model;

    /**
     * 校验并创建 Provider 配置。
     *
     * @param baseUrl OpenAI-compatible 服务基础地址
     * @param apiKey API Key，不得写入日志
     * @param model 服务端识别的模型名
     * @throws ProviderConfigurationException 任一必填项缺失或格式不合法
     */
    public OpenAiCompatibleSettings(String baseUrl, String apiKey, String model) {
        this.baseUrl = parseBaseUrl(requireValue("openai.base-url", baseUrl));
        this.apiKey = requireValue("openai.api-key", apiKey);
        this.model = validateModel(requireValue("openai.model", model));
    }

    /**
     * 返回已规范化的 Provider 基础地址。
     *
     * @return HTTP 或 HTTPS 绝对 URI
     */
    public URI baseUrl() {
        return baseUrl;
    }

    /**
     * 返回用于创建 Provider Client 的 API Key。
     *
     * <p>调用者不得记录、持久化或放入 Agent Event。</p>
     *
     * @return 非空 API Key
     */
    public String apiKey() {
        return apiKey;
    }

    /**
     * 返回 Provider 模型名。
     *
     * @return 非空模型名
     */
    public String model() {
        return model;
    }

    /**
     * 创建只替换模型名的新配置，保留 Base URL 与 Secret。
     *
     * <p>模型覆盖仍经过与配置文件相同的长度和控制字符校验。</p>
     *
     * @param modelOverride CLI 或其他可信配置层提供的模型名
     * @return 不修改当前实例的新设置
     */
    public OpenAiCompatibleSettings withModel(String modelOverride) {
        return new OpenAiCompatibleSettings(
                baseUrl.toString(),
                apiKey,
                modelOverride);
    }

    @Override
    public String toString() {
        return "OpenAiCompatibleSettings[baseUrl=" + baseUrl
                + ", apiKey=<redacted>, model=" + model + "]";
    }

    private static String requireValue(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new ProviderConfigurationException(
                    REQUIRED_VALUE_MISSING,
                    "Required provider setting is missing: " + key
            );
        }
        return value.trim();
    }

    private static URI parseBaseUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            boolean supportedScheme = "https".equalsIgnoreCase(scheme)
                    || "http".equalsIgnoreCase(scheme);
            if (!uri.isAbsolute()
                    || !supportedScheme
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw invalidBaseUrl();
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw invalidBaseUrl();
        }
    }

    private static ProviderConfigurationException invalidBaseUrl() {
        return new ProviderConfigurationException(
                INVALID_BASE_URL,
                "openai.base-url must be an absolute HTTP(S) URL without credentials, query or fragment"
        );
    }

    private static String validateModel(String value) {
        if (value.length() > MAX_MODEL_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new ProviderConfigurationException(
                    INVALID_MODEL,
                    "openai.model contains unsupported characters or exceeds the length limit"
            );
        }
        return value;
    }
}
