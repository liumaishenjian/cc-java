package io.github.liumaishenjian.ccjava.domain;

/**
 * 批准 Plan 到执行 Run 的上下文交接策略。
 *
 * <p>无论选择哪一种策略，批准工件身份和完整快照都必须由 ExecutionBrief 保留。</p>
 *
 * @since 0.1.0
 */
public enum PlanContextPolicy {
    /** 保留当前 canonical 对话上下文，并附加不可变批准工件。 */
    KEEP,
    /** 执行请求只投影基础系统上下文、ExecutionBrief 与批准工件。 */
    CLEAR
}
