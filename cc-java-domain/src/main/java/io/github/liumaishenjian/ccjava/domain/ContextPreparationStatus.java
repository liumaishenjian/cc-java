package io.github.liumaishenjian.ccjava.domain;

/**
 * Context Usage View 对应的准备或恢复终态。
 *
 * <p>该枚举只描述 Core 已完成的确定性阶段，不携带 Provider 文本、请求正文或异常详情。</p>
 *
 * @since 0.7.0
 */
public enum ContextPreparationStatus {
    /** 已完成普通 Projection 准备，尚未观察到 overflow recovery 终态。 */
    PREPARED,
    /** 已完成 typed overflow 后的单次恢复终态。 */
    OVERFLOW_RECOVERY_COMPLETED
}
