package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PlanApprovalGate;
import io.github.liumaishenjian.ccjava.domain.PlanDocument;
import io.github.liumaishenjian.ccjava.domain.PlanExecutionState;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import java.util.Objects;

/**
 * Session-owned Plan 的持久恢复投影；不包含可自动重放的操作。
 *
 * <p>构造器把 document/state 的身份、状态、摘要、步骤游标和审批 Gate 提升为 Core
 * 不变量。架构边缘即使绕过 JSONL codec 直接构造恢复快照，也不能把互相矛盾的两份
 * Plan 状态交给 {@link PlanModeCoordinator}。</p>
 *
 * @param document 用户可见 Plan 文档投影
 * @param state 与文档同一时刻的执行状态投影
 * @since 0.1.0
 */
public record PlanRecoveryProjection(PlanDocument document, PlanExecutionState state) {
    /** 验证身份、状态、摘要、Gate 与步骤游标的一致性。 */
    public PlanRecoveryProjection {
        document = Objects.requireNonNull(document, "document 不能为空");
        state = Objects.requireNonNull(state, "state 不能为空");
        if (!document.id().equals(state.planId())) throw new IllegalArgumentException("Plan ID 不匹配");
        if (document.status() != state.status()) throw new IllegalArgumentException("Plan 状态不匹配");
        if (!document.workspaceDigest().equals(state.workspaceDigest())) {
            throw new IllegalArgumentException("Plan workspaceDigest 不匹配");
        }
        if (!validState(state, document.steps().size())) {
            throw new IllegalArgumentException("Plan 执行投影不合法");
        }
    }

    private static boolean validState(PlanExecutionState state, int stepCount) {
        if (state.nextStep() != null && state.nextStep() > stepCount) return false;
        if (state.activeStep() != null && state.activeStep() > stepCount) return false;
        return switch (state.status()) {
            case DRAFT -> false;
            case AWAITING_APPROVAL -> state.approvalGate() == PlanApprovalGate.PENDING
                    && state.nextStep() != null && state.activeStep() == null;
            case APPROVED -> state.approvalGate() == PlanApprovalGate.APPROVED
                    && state.nextStep() != null && state.activeStep() == null;
            case EXECUTING -> state.approvalGate() == PlanApprovalGate.APPROVED
                    && state.nextStep() == null && state.activeStep() != null;
            case PAUSED -> state.approvalGate() == PlanApprovalGate.APPROVED
                    && state.nextStep() != null && state.activeStep() == null;
            case NEEDS_VERIFICATION, COMPLETED -> state.approvalGate() == PlanApprovalGate.APPROVED
                    && state.nextStep() == null && state.activeStep() == null;
            case REJECTED -> state.approvalGate() == PlanApprovalGate.REJECTED
                    && state.activeStep() == null;
            case DIGEST_CONFLICT, FAILED, CANCELLED, TIMED_OUT, LIMIT_EXCEEDED ->
                    state.activeStep() == null;
        };
    }
}
