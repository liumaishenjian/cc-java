package io.github.liumaishenjian.ccjava.domain.execution;

/**
 * 平台对某项安全能力的真实状态，而不是根据操作系统名称或配置推断的状态。
 *
 * @since 0.13.0
 */
public enum CapabilityStatus {
    /** 已由实际自测证明强制。 */
    ENFORCED,
    /** 仅部分强制，不能满足要求完整隔离的策略。 */
    DEGRADED,
    /** 当前主机或依赖不可用。 */
    UNAVAILABLE,
    /** 尚未验证，必须按不可用处理。 */
    UNKNOWN
}
