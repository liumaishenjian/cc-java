package io.github.liumaishenjian.ccjava.domain;

/**
 * Context Usage View 可公开的固定解释码。
 *
 * <p>所有成员均为封闭分类，禁止以自由文本承载 Prompt、路径、工具参数、模型异常或其他不可信内容。</p>
 *
 * @since 0.7.0
 */
public enum ContextUsageReasonCode {
    /** 根项目指令已与 SystemMessage 合并，不能单独精确归因。 */
    INSTRUCTIONS_COALESCED_WITH_SYSTEM,
    /** Provider 明确报告输入 Context overflow，Core 已结束至多一次恢复处理。 */
    TYPED_CONTEXT_OVERFLOW,
    /** overflow recovery 的摘要候选通过 Gate 并被采用。 */
    OVERFLOW_SUMMARY_ADOPTED,
    /** overflow recovery 未采用摘要候选。 */
    OVERFLOW_SUMMARY_UNCHANGED,
    /** overflow recovery 在完成前被取消或关闭。 */
    OVERFLOW_RECOVERY_CANCELLED
}
