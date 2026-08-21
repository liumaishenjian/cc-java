package io.github.liumaishenjian.ccjava.domain;

/**
 * Plan 从批准到实际执行之间被确定性 Gate 阻止的封闭原因。
 *
 * @since 0.1.0
 */
public enum PlanExecutionBlockReason {
    /** 实时 Workspace 摘要不再等于用户审批时绑定的摘要。 */
    WORKSPACE_DRIFT
}
