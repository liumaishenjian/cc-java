package io.github.liumaishenjian.ccjava.domain;

/** Plan 完成前必须被确定性证明的证据种类。 */
public enum PlanEvidenceKind {
    /** Workspace 内必须存在并通过路径/摘要验证的交付物。 */
    DELIVERABLE,
    /** 必须存在成功 Tool Result 的验证活动，例如测试命令。 */
    VERIFICATION
}
