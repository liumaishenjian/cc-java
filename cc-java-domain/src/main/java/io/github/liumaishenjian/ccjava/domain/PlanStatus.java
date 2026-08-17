package io.github.liumaishenjian.ccjava.domain;

/** 规划文档在审批与执行生命周期中的状态。 */
public enum PlanStatus {
    DRAFT,
    AWAITING_APPROVAL,
    APPROVED,
    EXECUTING,
    PAUSED,
    COMPLETED,
    REJECTED,
    DIGEST_CONFLICT,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    LIMIT_EXCEEDED
}
