package io.github.liumaishenjian.ccjava.domain;

/**
 * Provider-neutral 的模型回合结束原因。
 *
 * <p>Adapter 只能映射 Provider 明确返回的原因；未知或缺失值必须保留为
 * {@link #UNKNOWN}，不能根据文本内容推断。</p>
 *
 * @since 0.1.0
 */
public enum ModelFinishReason {
    /** 模型自然结束并给出最终内容。 */
    STOP,
    /** 模型请求一个或多个 Tool Call。 */
    TOOL_CALLS,
    /** 输出达到 Provider 长度上限。 */
    LENGTH,
    /** Provider 内容策略中止了输出。 */
    CONTENT_FILTER,
    /** Provider 返回了当前协议尚未标准化的明确原因。 */
    OTHER,
    /** Provider 未返回结束原因。 */
    UNKNOWN
}
