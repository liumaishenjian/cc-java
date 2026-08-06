package io.github.liumaishenjian.ccjava.domain;

/**
 * 模型失败发生的封闭验证阶段。
 *
 * <p>值只描述项目自有边界，不携带 SDK 类型、异常文本或 Provider 数据。</p>
 *
 * @since 0.1.0
 */
public enum ModelFailureStage {
    /** 请求尚未形成 Provider 响应时的传输。 */
    REQUEST_TRANSPORT,
    /** 已收到至少一个 Provider frame 后的流传输。 */
    STREAM_TRANSPORT,
    /** Provider 响应向项目协议解码。 */
    RESPONSE_DECODE,
    /** 完成原因存在性与一致性校验。 */
    FINISH_METADATA,
    /** Tool arguments JSON 向封闭参数对象转换。 */
    TOOL_ARGUMENTS
}
