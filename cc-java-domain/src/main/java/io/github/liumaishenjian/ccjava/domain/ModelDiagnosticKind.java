package io.github.liumaishenjian.ccjava.domain;

/**
 * 模型诊断事件种类。
 *
 * <p>SAFE 只允许 {@link #FAILURE}；VERBOSE 可记录其余固定生命周期事件，且不会
 * 因种类变化增加原始请求或响应字段。</p>
 *
 * @since 0.1.0
 */
public enum ModelDiagnosticKind {
    /** 一次模型尝试开始。 */
    ATTEMPT_STARTED,
    /** 首个 Provider frame 已到达。 */
    PROVIDER_FRAME_RECEIVED,
    /** 一次模型尝试成功完成。 */
    ATTEMPT_COMPLETED,
    /** 一次模型尝试失败。 */
    FAILURE
}
