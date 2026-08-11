package io.github.liumaishenjian.ccjava.core.network;

import java.util.Objects;

/**
 * 网络访问端口的封闭决定。
 *
 * @param allowed 是否允许
 * @param reason 固定原因
 * @param controlled 是否所有创建/执行步骤均受端口控制
 * @since 0.1.0
 */
public record NetworkAccessDecision(boolean allowed, NetworkAccessReason reason, boolean controlled) {
    /** 校验固定原因与 controlled 声明一致。 */
    public NetworkAccessDecision {
        reason = Objects.requireNonNull(reason, "reason 不能为空");
        if (controlled && reason == NetworkAccessReason.UNSUPPORTED_CONTROL) {
            throw new IllegalArgumentException("UNSUPPORTED_CONTROL 不能标记为 controlled");
        }
    }
    /**
     * 创建受端口完整控制的允许决定。
     *
     * @return 固定 POLICY_ALLOWED 决定
     */
    public static NetworkAccessDecision allow() {
        return new NetworkAccessDecision(true, NetworkAccessReason.POLICY_ALLOWED, true);
    }
    /**
     * 创建拒绝或无法控制的决定。
     *
     * @param reason 拒绝或不受控的固定原因
     * @return 与原因匹配的封闭决定
     */
    public static NetworkAccessDecision deny(NetworkAccessReason reason) {
        return new NetworkAccessDecision(false, reason, reason != NetworkAccessReason.UNSUPPORTED_CONTROL);
    }
}
