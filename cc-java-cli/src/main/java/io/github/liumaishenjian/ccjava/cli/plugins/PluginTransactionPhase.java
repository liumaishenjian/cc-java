package io.github.liumaishenjian.ccjava.cli.plugins;

/** Plugin transaction 已 durable 的封闭阶段。 */
public enum PluginTransactionPhase {
    /** 事务已声明但尚未写入 staging。 */
    PREPARED,
    /** Staging 内容与摘要已持久化。 */
    STAGED,
    /** Staging 内容已完成身份复验。 */
    VERIFIED,
    /** 新内容或迁移后的索引已发布。 */
    PUBLISHED,
    /** Registry 已持久化对应变更。 */
    REGISTRY_COMMITTED,
    /** 卸载目标已进入拒绝新 lease 的静默阶段。 */
    QUIESCING,
    /** 物理删除失败并持久化 tombstone。 */
    TOMBSTONED,
    /** 事务所有步骤已经干净完成。 */
    COMPLETED,
    /** 无法证明安全恢复，保留现场等待人工处置。 */
    FAILED_PRESERVED
}
