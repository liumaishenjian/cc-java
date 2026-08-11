package io.github.liumaishenjian.ccjava.core.plugin;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginId;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginRegistryState;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshotSet;
import java.util.List;
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
    /**
     * 检查精确 fingerprint 是否通过宿主信任 Gate。
     *
     * @param snapshot 待激活 snapshot
     * @return 通过时为 true
     */
    boolean isTrusted(PluginSnapshot snapshot);

    /**
     * 准备可回滚的 generation 激活，不改变当前 active。
     *
     * @param snapshot 待激活 snapshot
     * @return 调用方必须 commit 或 close 的 activation
     */
    PluginActivation prepareActivation(PluginSnapshot snapshot);

    /**
     * 以兼容旧调用方的单步形式激活可信 snapshot。
     *
     * @param snapshot 通过 trust Gate 的 snapshot
     */
    default void activate(PluginSnapshot snapshot) {
        try (PluginActivation activation = prepareActivation(snapshot)) {
            activation.commit();
        }
    }

    /**
     * 查询当前 ACTIVE 项。
     *
     * @return 稳定不可变快照，不签发 lease
     */
    PluginSnapshotSet activeSnapshot();

    /**
     * 为 ACTIVE Plugin 签发 lease。
     *
     * @param pluginId Plugin identity
     * @return QUIESCING/不存在时为空
     */
    Optional<PluginLease> acquire(PluginId pluginId);

    /**
     * 将 ACTIVE 原子转换为 QUIESCING。
     *
     * @param pluginId Plugin identity
     * @return 不存在或已转换时为 false
     */
    boolean beginQuiescing(PluginId pluginId);

    /**
     * 在 QUIESCING 且引用归零时移出 Registry。
     *
     * @param pluginId Plugin identity
     * @return 可由 Adapter 删除的固定 snapshot；否则为空
     */
    Optional<PluginSnapshot> completeRemoval(PluginId pluginId);

    /**
     * 标记 Adapter 已完成物理删除，使生命周期进入不可查询状态。
     *
     * @param pluginId 已完成物理删除的 Plugin
     */
    void markDeleted(PluginId pluginId);

    /**
     * 标记物理删除失败并保留 tombstone，避免静默恢复为可用状态。
     *
     * @param pluginId 物理删除失败并需保留 tombstone 的 Plugin
     */
    void markTombstoned(PluginId pluginId);

    /**
     * 查询 Plugin 生命周期状态。
     *
     * @param pluginId Plugin identity
     * @return 从未注册或已成功删除时为空
     */
    Optional<PluginRegistryState> state(PluginId pluginId);

    /**
     * 查询当前 active generation 引用数。
     *
     * @param pluginId Plugin identity
     * @return lease 数量
     */
    int leaseCount(PluginId pluginId);

    /**
     * 取得并消费所有 lease 已归零的 retired generations。
     *
     * @return 按退役顺序排列且每代最多返回一次的回收任务
     */
    List<RetiredPluginGeneration> drainRetiredReady();
}
