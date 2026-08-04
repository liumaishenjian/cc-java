package io.github.liumaishenjian.ccjava.domain;

/**
 * M1/M2 持久变更的确定性终态。
 *
 * @since 0.7.0
 */
public enum MemoryMutationStatus {
    /** 新 topic 已提交到 M1。 */
    CREATED,

    /** 已有 topic 在摘要匹配后被替换。 */
    UPDATED,

    /** 已有 topic 在摘要匹配后被删除。 */
    DELETED,

    /** M2 已从当前安全 Catalog 重建并持久化。 */
    INDEX_REBUILT,

    /** 变更在提交 M1 前被拒绝或失败。 */
    REJECTED
}
