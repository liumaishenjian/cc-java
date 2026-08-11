package io.github.liumaishenjian.ccjava.core.session;

/** Retention 决定的固定原因。 */
public enum RetentionReason {
    /** 当前状态与确认允许执行动作。 */
    ALLOWED,
    /** 缺少动作所需的一次或二次确认。 */
    CONFIRMATION_REQUIRED,
    /** Session 仍有 active writer。 */
    ACTIVE,
    /** Canonical 状态无法可靠证明。 */
    UNCERTAIN,
    /** 存在未完成副作用，禁止删除。 */
    INCOMPLETE_SIDE_EFFECT,
    /** Session migration fence 仍在。 */
    MIGRATING
}
