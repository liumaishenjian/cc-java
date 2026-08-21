package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 通知 Surface：已批准 Plan 在副作用开始前被安全 Gate 拦截。
 *
 * <p>事件只携带稳定身份、摘要和封闭原因，不表示任何 Tool 或模型调用已经发生。
 * {@code recoveryStatus} 是持久工件在拦截后可供 Surface 展示的明确恢复状态。</p>
 *
 * @param planId 被拦截的 Plan 身份
 * @param approvedRevision 用户批准的正文 revision
 * @param approvedWorkspaceDigest 审批时绑定的 Workspace 摘要
 * @param currentWorkspaceDigest 执行边界重新计算的 Workspace 摘要
 * @param reason 封闭阻止原因
 * @param recoveryStatus 已持久化的恢复状态
 * @since 0.1.0
 */
public record PlanExecutionBlockedEvent(
        String planId,
        long approvedRevision,
        String approvedWorkspaceDigest,
        String currentWorkspaceDigest,
        PlanExecutionBlockReason reason,
        PlanStatus recoveryStatus) implements AgentEvent {
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    /** 验证事件只表达可重新审批的 Workspace 漂移。 */
    public PlanExecutionBlockedEvent {
        planId = Objects.requireNonNull(planId, "planId 不能为空");
        approvedWorkspaceDigest = Objects.requireNonNull(approvedWorkspaceDigest, "approvedWorkspaceDigest 不能为空");
        currentWorkspaceDigest = Objects.requireNonNull(currentWorkspaceDigest, "currentWorkspaceDigest 不能为空");
        reason = Objects.requireNonNull(reason, "reason 不能为空");
        recoveryStatus = Objects.requireNonNull(recoveryStatus, "recoveryStatus 不能为空");
        if (planId.isBlank() || approvedRevision < 1
                || !SHA.matcher(approvedWorkspaceDigest).matches()
                || !SHA.matcher(currentWorkspaceDigest).matches()
                || recoveryStatus != PlanStatus.AWAITING_APPROVAL) {
            throw new IllegalArgumentException("Plan execution blocked 事件无效");
        }
    }
}
