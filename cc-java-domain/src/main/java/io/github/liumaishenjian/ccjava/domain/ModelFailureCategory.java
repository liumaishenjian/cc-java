package io.github.liumaishenjian.ccjava.domain;

/**
 * 模型调用失败的 Provider-neutral 安全分类。
 *
 * <p>枚举只表达用户可以采取行动的稳定类别，不携带 Provider 响应正文、端点、
 * Request ID、Prompt 或 Secret。Adapter 负责把底层 SDK 异常映射到这些值。</p>
 *
 * @since 0.1.0
 */
public enum ModelFailureCategory {
    /** Provider 服务端当前不可用。 */
    PROVIDER_UNAVAILABLE,
    /** Provider 返回限流。 */
    RATE_LIMITED,
    /** 模型请求超时。 */
    REQUEST_TIMEOUT,
    /** Provider 报告请求冲突。 */
    REQUEST_CONFLICT,
    /** 凭证缺失、无效或无权访问。 */
    AUTHENTICATION_FAILED,
    /** 请求被 Provider 确定性拒绝。 */
    INVALID_REQUEST,
    /** 网络、连接或传输失败。 */
    NETWORK_ERROR,
    /** 已收到响应或可见输出，但流未完整结束。 */
    INCOMPLETE_STREAM,
    /** Provider 完成数据缺失、矛盾或无法解析。 */
    INVALID_RESPONSE,
    /** 无法安全细分的 Provider 错误。 */
    PROVIDER_ERROR,
    /** 本地 Provider profile 或模型选择尚未配置。 */
    CONFIGURATION_REQUIRED
}
