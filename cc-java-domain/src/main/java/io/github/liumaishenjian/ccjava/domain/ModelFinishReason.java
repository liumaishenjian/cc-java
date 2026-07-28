package io.github.liumaishenjian.ccjava.domain;

/**
 * 归一化不同模型 Provider 对单个回合结束原因的表达。
 *
 * <p>该枚举只描述已经聚合完成的模型回合为什么停止，不负责决定整个
 * Agent Run 的终态。Provider 没有返回可靠原因时必须使用
 * {@link #UNKNOWN}，不能根据文本长度或 Token 估算伪造结束原因。</p>
 *
 * @since 0.1.0
 */
public enum ModelFinishReason {

    /** 模型正常结束本回合，通常表示已经给出最终文本。 */
    STOP,

    /** 模型结束本回合并请求一个或多个 Tool Call。 */
    TOOL_CALLS,

    /** 模型输出达到 Provider 或请求配置的长度上限。 */
    LENGTH,

    /** Provider 因内容过滤停止输出。 */
    CONTENT_FILTER,

    /** Provider 未提供原因，或返回了当前版本尚未识别的原因。 */
    UNKNOWN
}
