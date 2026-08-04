package io.github.liumaishenjian.ccjava.domain;

/**
 * S07 条件式 Context Reduction 策略。
 *
 * <p>C1/C2 使用确定性本地 Reducer；C3/C4 只有候选通过 revision、覆盖、预算、协议与
 * protected anchor Gate 后才允许报告为已应用。</p>
 *
 * @since 0.7.0
 */
public enum ContextReductionStrategy {

    /** C1：缩减单个高体积载荷。 */
    LARGE_PAYLOAD_REDUCTION,

    /** C2：清理低价值的旧 Tool Result 正文。 */
    OLD_TOOL_RESULT_CLEANUP,

    /** C3：经验证后归纳已完成历史。 */
    ROLLING_MEMORY,

    /** C4：经摘要提交 Gate 验证后归纳完整边界。 */
    FULL_SUMMARY
}
