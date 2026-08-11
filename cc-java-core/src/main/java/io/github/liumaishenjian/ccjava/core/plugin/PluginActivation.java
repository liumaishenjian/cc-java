package io.github.liumaishenjian.ccjava.core.plugin;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;

/**
 * Plugin active generation 切换的进程内 prepare/commit/rollback 事务。
 *
 * <p>prepare 只验证 trust 与当前 generation，不改变可见 active；commit 原子切换且最多一次，
 * rollback 在 commit 前取消，或在 durable publish 回滚后恢复旧 active。调用者必须在关闭前明确
 * commit 或 rollback。</p>
 *
 * @since 0.11.0
 */
public interface PluginActivation extends AutoCloseable {
    /**
     * 返回准备激活的新 immutable snapshot。
     *
     * @return 本事务绑定的候选 snapshot
     */
    PluginSnapshot candidate();
    /** 原子发布 candidate；并发状态漂移时失败且不改变 active。 */
    void commit();
    /** 幂等取消或恢复本事务切换前的 active generation。 */
    void rollback();
    /** 未完成事务默认回滚。 */
    @Override
    void close();
}
