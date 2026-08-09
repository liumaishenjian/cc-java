package io.github.liumaishenjian.ccjava.core.plugin;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginId;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginRegistryState;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshotSet;
import java.util.Optional;

/**
 * 管理可信 Plugin snapshot、Session lease 与 quiescing 卸载的 Core 契约。
 *
 * <p>Registry 不访问文件系统，也不删除内容。Adapter 只有在 {@link #completeRemoval(PluginId)}
 * 成功返回待删除 snapshot 后才能尝试物理删除。</p>
 *
 * @since 0.11.0
 */
public interface PluginRegistry {

    /** @return 精确 fingerprint 是否通过 Registry 的宿主信任 Gate */
    boolean isTrusted(PluginSnapshot snapshot);

    /** 准备可回滚的 generation 激活，不改变当前 active。 */
    PluginActivation prepareActivation(PluginSnapshot snapshot);

    /** 便捷原子激活；内部使用 prepare/commit。 */
    default void activate(PluginSnapshot snapshot) {
        try (PluginActivation activation = prepareActivation(snapshot)) {
            activation.commit();
        }
    }

    /** @return 当前 ACTIVE 项的稳定不可变快照，不签发 lease */
    PluginSnapshotSet activeSnapshot();

    /** 为 ACTIVE Plugin 签发 lease；QUIESCING/不存在时为空。 */
    Optional<PluginLease> acquire(PluginId pluginId);

    /** 将 ACTIVE 原子转换为 QUIESCING；不存在或已转换时返回 {@code false}。 */
    boolean beginQuiescing(PluginId pluginId);

    /**
     * 在 QUIESCING 且引用归零时移出 Registry。
     *
     * @return 可由 Adapter 删除的固定 snapshot；否则为空
     */
    Optional<PluginSnapshot> completeRemoval(PluginId pluginId);

    /** 物理删除成功后清除 REMOVED 项。 */
    void markDeleted(PluginId pluginId);

    /** 物理删除失败后把 REMOVED 项转换为不含路径和异常文本的 tombstone。 */
    void markTombstoned(PluginId pluginId);

    /** @return 当前状态；从未注册或已成功删除时为空 */
    Optional<PluginRegistryState> state(PluginId pluginId);

    /** @return 当前 active generation 的引用数，用于确定性生命周期协调 */
    int leaseCount(PluginId pluginId);

    /**
     * 取得并消费所有 lease 已归零的 retired generations；每个 generation 最多返回一次。
     *
     * @return 按退役顺序排列的回收任务
     */
    java.util.List<RetiredPluginGeneration> drainRetiredReady();
}
