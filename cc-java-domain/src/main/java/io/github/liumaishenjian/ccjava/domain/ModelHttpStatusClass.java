package io.github.liumaishenjian.ccjava.domain;

/**
 * 允许进入 Surface 的粗粒度 HTTP 状态组。
 *
 * <p>不暴露精确状态码能够保持协议稳定，并避免把 Provider-specific 细节扩散到
 * Runtime 和终端。无 HTTP 响应的网络或流错误不携带该字段。</p>
 *
 * @since 0.1.0
 */
public enum ModelHttpStatusClass {
    /** HTTP 4xx 请求或鉴权错误。 */
    CLIENT_ERROR,
    /** HTTP 5xx Provider 服务错误。 */
    SERVER_ERROR
}
