package io.github.liumaishenjian.ccjava.domain.model;

/**
 * Provider/Model 可以显式声明或通过探测证明的能力维度。
 *
 * @since 0.1.0
 */
public enum ModelCapability {
    /** 普通文本输入输出。 */
    TEXT,
    /** 增量流式输出。 */
    STREAMING,
    /** 单个模型回合提出 Tool Call。 */
    TOOL_CALLING,
    /** 单个模型回合提出多个有序 Tool Call。 */
    MULTI_TOOL_CALLING,
    /** 返回可用于预算与观测的 Usage。 */
    USAGE,
    /** 传播并响应调用取消。 */
    CANCELLATION,
    /** 支持 Provider 原生 Prompt Cache。 */
    PROMPT_CACHE,
    /** 支持 Provider 原生 Context Editing。 */
    NATIVE_CONTEXT_EDITING,
    /** 失败响应提供可解释的 Retry-After。 */
    RETRY_AFTER,
    /** 支持独立于最终文本的 Reasoning 通道。 */
    REASONING
}
