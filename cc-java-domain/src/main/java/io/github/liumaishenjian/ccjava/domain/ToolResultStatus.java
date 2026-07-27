package io.github.liumaishenjian.ccjava.domain;

/**
 * Tool Result 的规范化执行状态。
 *
 * @since 0.1.0
 */
public enum ToolResultStatus {

    /** Tool 已成功执行并产生可反馈给模型的结果。 */
    SUCCESS,

    /** 调用未成功，但错误已结构化，可由模型修正后继续。 */
    FAILURE,

    /** 权限或审批明确拒绝了本次调用。 */
    DENIED
}
