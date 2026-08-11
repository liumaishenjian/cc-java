package io.github.liumaishenjian.ccjava.cli.plugins;

import java.util.Objects;

/**
 * Plugin transaction journal 的 durable 状态记录。
 *
 * @param transactionId 宿主生成的事务身份
 * @param pluginId Plugin 身份；registry migration 固定为 registry
 * @param operation install、uninstall 或 registry migration
 * @param phase 最后 durable phase
 * @param digest 事务绑定内容的 SHA-256
 */
public record PluginTransactionRecord(
        String transactionId,
        String pluginId,
        PluginTransactionOperation operation,
        PluginTransactionPhase phase,
        String digest) {
    /** 校验事务、Plugin、操作、阶段与摘要身份均为规范值。 */
    public PluginTransactionRecord {
        if (transactionId == null
                || !transactionId.matches("[a-zA-Z0-9_-]{1,128}")
                || pluginId == null
                || !pluginId.matches("[a-z0-9][a-z0-9-]{0,63}")
                || digest == null
                || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("plugin transaction record 非法");
        }
        operation = Objects.requireNonNull(operation, "operation 不能为空");
        phase = Objects.requireNonNull(phase, "phase 不能为空");
    }

    static PluginTransactionRecord parse(String line) {
        String[] fields = line.split("\\t", -1);
        if (fields.length != 5) {
            throw new IllegalArgumentException("plugin transaction line 非法");
        }
        return new PluginTransactionRecord(
                fields[0],
                fields[1],
                PluginTransactionOperation.valueOf(fields[2]),
                PluginTransactionPhase.valueOf(fields[3]),
                fields[4]);
    }
}
