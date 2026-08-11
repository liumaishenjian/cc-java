package io.github.liumaishenjian.ccjava.core.session;

/** Session retention/migration 使用的封闭生命周期状态。 */
public enum SessionLifecycleStatus {
    /** Session 存在活动 writer。 */
    ACTIVE,
    /** Session 已干净关闭。 */
    CLOSED,
    /** Session canonical 内容保留但已归档。 */
    ARCHIVED,
    /** Canonical 状态无法可靠证明。 */
    UNCERTAIN,
    /** Session 含未完成副作用，禁止破坏性 retention。 */
    INCOMPLETE_SIDE_EFFECT,
    /** Session migration fence 仍在。 */
    MIGRATING
}
