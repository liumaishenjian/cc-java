package io.github.liumaishenjian.ccjava.core.session;

import java.util.Objects;

/**
 * 默认 archive、永久删除二次确认且拒绝不安全状态的 Retention Policy。
 *
 * @since 0.1.0
 */
public final class SessionRetentionPolicy {
    /** 创建无状态的 Session retention 决策器。 */
    public SessionRetentionPolicy() { }

    /**
     * 根据服务端状态、动作和确认生成确定性决定。
     *
     * @param status 当前 canonical 生命周期状态
     * @param action 请求的 retention 动作
     * @param firstConfirmation 第一次显式确认
     * @param secondConfirmation 永久删除所需第二次确认
     * @return 允许标志、动作与固定原因
     */
    public RetentionDecision plan(SessionLifecycleStatus status, RetentionAction action, boolean firstConfirmation, boolean secondConfirmation) {
        Objects.requireNonNull(status, "status 不能为空"); Objects.requireNonNull(action, "action 不能为空");
        RetentionReason blocked = switch (status) { case ACTIVE -> RetentionReason.ACTIVE; case UNCERTAIN -> RetentionReason.UNCERTAIN; case INCOMPLETE_SIDE_EFFECT -> RetentionReason.INCOMPLETE_SIDE_EFFECT; case MIGRATING -> RetentionReason.MIGRATING; default -> null; };
        if (blocked != null) return new RetentionDecision(false, action, blocked);
        if (action == RetentionAction.PERMANENT_DELETE && !(firstConfirmation && secondConfirmation)) return new RetentionDecision(false, action, RetentionReason.CONFIRMATION_REQUIRED);
        return new RetentionDecision(true, action, RetentionReason.ALLOWED);
    }
}
