package io.github.liumaishenjian.ccjava.core.session;

/** Session retention 动作。 */
public enum RetentionAction {
    /** 保留 canonical 内容并标记为归档。 */
    ARCHIVE,
    /** 经二次确认后永久删除 canonical Session。 */
    PERMANENT_DELETE
}
