package io.github.liumaishenjian.ccjava.core.network;

/** 网络访问决定的固定原因。 */
public enum NetworkAccessReason {
    /** 目标与用途通过策略。 */
    POLICY_ALLOWED,
    /** 目标或用途被策略拒绝。 */
    POLICY_DENIED,
    /** 调用在授权前已经取消。 */
    CANCELLED,
    /** 请求剩余期限已耗尽。 */
    DEADLINE_EXPIRED,
    /** 当前 SDK 路径无法由端口完整控制。 */
    UNSUPPORTED_CONTROL,
    /** scheme、host 或 port 不满足固定目标契约。 */
    INVALID_TARGET
}
