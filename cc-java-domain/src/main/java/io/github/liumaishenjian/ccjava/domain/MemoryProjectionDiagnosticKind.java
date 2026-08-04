package io.github.liumaishenjian.ccjava.domain;

/**
 * M4/M5 不回显正文的降级原因。
 *
 * @since 0.7.0
 */
public enum MemoryProjectionDiagnosticKind {
    /** 召回消费时工作尚未完成。 */
    NOT_READY,
    /** 同一 ready-only 句柄已经被其他调用者消费。 */
    ALREADY_CONSUMED,
    /** 召回工作失败。 */
    RECALL_FAILED,
    /** 召回已取消。 */
    CANCELLED,
    /** Catalog revision 已变化。 */
    STALE_CATALOG,
    /** Topic 缺失、损坏或无法安全读取。 */
    TOPIC_UNAVAILABLE,
    /** Topic digest 与 Catalog 不匹配。 */
    DIGEST_MISMATCH,
    /** 重复 topic 被拒绝。 */
    DUPLICATE_TOPIC,
    /** 总正文预算不足，后续候选未投影。 */
    BUDGET_EXHAUSTED
}
