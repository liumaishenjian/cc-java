package io.github.liumaishenjian.ccjava.domain;

/**
 * S07 条件式 Context Reduction 策略。
 *
 * <p>枚举同时固定 C3/C4 的安全扩展缝隙；G3-A 只实现确定性的 C1/C2，Core 不得把
 * 尚未实现的策略报告为已应用。</p>
 *
 * @since 0.7.0
 */
public enum ContextReductionStrategy {

    /** C1：缩减单个高体积载荷。 */
    LARGE_PAYLOAD_REDUCTION,

    /** C2：清理低价值的旧 Tool Result 正文。 */
    OLD_TOOL_RESULT_CLEANUP,

    /** C3：经验证后归纳已完成历史；当前尚未实现。 */
    ROLLING_MEMORY,

    /** C4：经摘要提交 Gate 验证后归纳完整边界；当前尚未实现。 */
    FULL_SUMMARY
}
