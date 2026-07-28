package io.github.liumaishenjian.ccjava.core;

/**
 * 模型 Adapter 向 Runtime 报告的 Provider-neutral 失败分类。
 *
 * <p>分类只用于确定终止、重试和用户可读诊断，不暴露 Provider SDK
 * 异常、HTTP Header 或可能包含 Secret 的原始响应。</p>
 *
 * @since 0.1.0
 */
public enum ModelFailureKind {

    /** 用户主动取消了当前模型请求。 */
    CANCELLED,

    /** 当前 Run 或模型请求超过截止时间。 */
    DEADLINE_EXCEEDED,

    /** Provider 在输出完整回合前中断了流。 */
    INCOMPLETE_RESPONSE,

    /** Provider 明确返回限流。 */
    RATE_LIMITED,

    /** Provider 暂时不可用，且调用可能安全重试。 */
    TEMPORARILY_UNAVAILABLE,

    /** Provider 凭证缺失或无效。 */
    AUTHENTICATION_FAILED,

    /** 请求配置、消息或 Tool Schema 被 Provider 拒绝。 */
    INVALID_REQUEST,

    /** Provider 返回了无法转换为项目协议的响应。 */
    INVALID_RESPONSE,

    /** Adapter 为保护本地内存而拒绝超过聚合上限的模型响应。 */
    RESPONSE_LIMIT_EXCEEDED,

    /** 当前版本无法进一步分类的 Provider 或 Adapter 故障。 */
    UNKNOWN
}
