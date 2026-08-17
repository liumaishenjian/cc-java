package io.github.liumaishenjian.ccjava.domain;

/** 规划审批结果；未批准前任何普通副作用 Tool 都不得执行。 */
public enum PlanApprovalGate {
    PENDING,
    APPROVED,
    REJECTED
}
