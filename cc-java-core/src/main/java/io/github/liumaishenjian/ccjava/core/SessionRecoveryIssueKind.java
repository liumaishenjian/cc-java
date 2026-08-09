package io.github.liumaishenjian.ccjava.core;

/**
 * Session journal 重放后需要阻止可写恢复的稳定问题分类。
 *
 * @since 0.6.0
 */
public enum SessionRecoveryIssueKind {

    /** Session 由 Inspect 只读打开，不允许进入 Runtime。 */
    READ_ONLY_INSPECT,

    /** 最后一条记录因进程中断而不完整。 */
    DAMAGED_TAIL,

    /** Run 没有 durable completed 终态。 */
    UNFINISHED_RUN,

    /** Assistant Tool Call 没有 resolved 或 started。 */
    TOOL_NOT_STARTED,

    /** Tool 已 started，但没有包含完整 Result 的 completed。 */
    TOOL_COMPLETION_UNKNOWN,

    /** 未完成 Tool 的可信 Effect 可能已经产生副作用。 */
    POTENTIAL_SIDE_EFFECT,

    /** Undo metadata 表明操作已准备或应用，但 durable journal 终态无法安全确认。 */
    CHECKPOINT_UNDO_UNCERTAIN,

    /** Skill activation 没有 durable completed，恢复不得猜测或重放。 */
    SKILL_INVOCATION_UNFINISHED,

    /** 历史 Skill identity/content 与当前受信 catalog 不一致。 */
    SKILL_RECOVERY_MISMATCH
}
