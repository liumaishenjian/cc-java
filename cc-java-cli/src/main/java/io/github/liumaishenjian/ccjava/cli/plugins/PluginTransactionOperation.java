package io.github.liumaishenjian.ccjava.cli.plugins;

/** Plugin durable transaction 的封闭操作类型。 */
public enum PluginTransactionOperation {
    /** 发布新的 Plugin snapshot 与 registry entry。 */
    INSTALL,
    /** Quiesce 后删除或保留 Plugin snapshot。 */
    UNINSTALL,
    /** 将调用方确认的 legacy registry 转换为 v1。 */
    REGISTRY_MIGRATION
}
