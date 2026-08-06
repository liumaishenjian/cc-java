package io.github.liumaishenjian.ccjava.domain;

/**
 * 模型诊断使用的封闭失败原因。
 *
 * <p>Adapter 只能根据已知异常类型或项目自有验证点选择这些值，禁止解析任意异常
 * message 推断原因。</p>
 *
 * @since 0.1.0
 */
public enum ModelFailureReason {
    /** 传输在完整模型回合前关闭。 */
    TRANSPORT_CLOSED,
    /** 已知网络 I/O 类型失败。 */
    NETWORK_IO,
    /** 已知超时类型失败。 */
    TIMEOUT,
    /** 响应结构不能转换为项目协议。 */
    INVALID_RESPONSE,
    /** 缺少受支持的 finish metadata。 */
    FINISH_MISSING,
    /** finish metadata 与响应内容不一致。 */
    FINISH_INCONSISTENT,
    /** Tool arguments 不是项目接受的 JSON object。 */
    TOOL_JSON_INVALID,
    /** 已知验证点存在失败但不能安全细分。 */
    UNKNOWN
}
