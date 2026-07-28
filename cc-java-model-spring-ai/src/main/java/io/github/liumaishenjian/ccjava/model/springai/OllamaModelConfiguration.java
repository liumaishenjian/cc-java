package io.github.liumaishenjian.ccjava.model.springai;

import java.net.URI;
import java.util.Objects;

/**
 * S02 首个真实 Provider（Ollama）的显式配置。
 *
 * <p>配置只保存连接端点、模型标识和单次输出边界，不保存 Prompt、API Key
 * 或任意 Provider 响应。当前端点必须是无用户信息、无查询参数的 HTTP(S)
 * URI；环境变量和 CLI 参数的来源优先级由 Composition Root 决定。</p>
 *
 * @param baseUrl         Ollama HTTP API 根地址
 * @param model           已安装的模型名称或不可变 Digest 标识
 * @param maxOutputTokens 单个模型回合允许生成的最大 Token 数
 * @param temperature     Provider 温度参数，范围为 0 到 2
 * @param thinkingEnabled 是否请求 Provider 输出 Thinking
 * @since 0.1.0
 */
public record OllamaModelConfiguration(
        URI baseUrl,
        String model,
        int maxOutputTokens,
        double temperature,
        boolean thinkingEnabled) {

    /** Ollama 默认本机端点。 */
    public static final URI DEFAULT_BASE_URL = URI.create("http://localhost:11434");

    /**
     * 校验 Provider 配置。
     *
     * @param baseUrl Ollama 根地址
     * @param model 模型标识
     * @param maxOutputTokens 单回合输出上限
     * @param temperature 温度参数
     * @param thinkingEnabled 是否启用 Thinking
     * @throws NullPointerException URI 或模型标识为空时
     * @throws IllegalArgumentException URI、模型标识或数值边界无效时
     */
    public OllamaModelConfiguration {
        baseUrl = Objects.requireNonNull(baseUrl, "baseUrl 不能为空");
        model = requireText(model, "model");
        String scheme = baseUrl.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("baseUrl 只允许 http 或 https");
        }
        if (baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException(
                    "baseUrl 必须包含主机，且不能包含凭证、查询参数或 Fragment");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > 1_000_000) {
            throw new IllegalArgumentException(
                    "maxOutputTokens 必须在 1 到 1000000 之间");
        }
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("temperature 必须是 0 到 2 的有限数");
        }
    }

    /**
     * 创建适合可复现实验的保守配置。
     *
     * @param model 本机已安装的模型
     * @return 固定本机端点、4096 输出上限、零温度且关闭 Thinking 的配置
     */
    public static OllamaModelConfiguration local(String model) {
        return new OllamaModelConfiguration(
                DEFAULT_BASE_URL,
                model,
                4_096,
                0,
                false);
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空白");
        }
        return value;
    }
}
