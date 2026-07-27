package io.github.liumaishenjian.ccjava.domain;

/**
 * Agent Run 的高层完成状态。
 *
 * @since 0.1.0
 */
public enum RunStatus {

    /** 正常得到模型最终回复。 */
    COMPLETED,

    /** 因确定性限制或无效输入而安全停止。 */
    STOPPED,

    /** 因模型或 Runtime 错误失败。 */
    FAILED,

    /** 由用户取消。 */
    CANCELLED
}
