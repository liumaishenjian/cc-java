package io.github.liumaishenjian.ccjava.domain;

/**
 * Permission Gate 对单次 Tool 调用给出的最小决策。
 *
 * <p>S01 只定义并验证端口连接；完整模式、规则优先级、Session 授权和
 * Hard Denial 属于 S04～S05。</p>
 *
 * @since 0.1.0
 */
public enum PermissionDecision {

    /** 无需人工参与即可继续。 */
    ALLOW,

    /** 必须交给 Approval Handler 决定。 */
    ASK,

    /** 确定性拒绝本次调用。 */
    DENY
}
