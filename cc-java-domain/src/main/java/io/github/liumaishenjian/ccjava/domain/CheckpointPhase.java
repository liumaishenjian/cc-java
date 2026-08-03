package io.github.liumaishenjian.ccjava.domain;

/**
 * 可安全投影给 CLI/stdio/TUI 的 Checkpoint durable 阶段。
 *
 * <p>只有 {@link #COMPLETED_PRESENT} 与 {@link #COMPLETED_ABSENT} 表示规范
 * {@code checkpoint.completed} 已提交且允许显式 Undo。任何 uncertain 阶段都必须原样展示，
 * 不能压缩成简单布尔值或被当作可自动恢复状态。</p>
 *
 * @since 0.6.0
 */
public enum CheckpointPhase {

    /** pre-image 已准备，本地进程尚未确认 created journal。 */
    CREATE_PREPARED,
    /** created journal 调用结果不确定，必须保留 pre-image。 */
    CREATE_JOURNAL_UNCERTAIN,
    /** created 已提交，Tool 尚未提供 post-state。 */
    CREATED,
    /** post-state 已准备，本地进程尚未确认 completed journal。 */
    POST_PREPARED,
    /** completed journal 调用结果不确定。 */
    POST_JOURNAL_UNCERTAIN,
    /** completed 已提交且 post-state 是普通文件 digest。 */
    COMPLETED_PRESENT,
    /** completed 已提交且 post-state 已知为不存在。 */
    COMPLETED_ABSENT,
    /** Undo 已确认并准备，但尚未证明 Workspace 修改是否应用。 */
    UNDO_PREPARED,
    /** Undo Workspace 修改已应用，journal 尚未确认。 */
    UNDO_APPLIED,
    /** Undo journal 调用结果不确定，禁止自动重试。 */
    UNDO_JOURNAL_UNCERTAIN,
    /** Undo journal 已提交。 */
    UNDONE
}
