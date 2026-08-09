package io.github.liumaishenjian.ccjava.core.plugin;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;

/**
 * Session 对固定 Plugin snapshot 的引用计数 lease。
 *
 * <p>lease 必须关闭；关闭幂等且只释放一次引用。进入 QUIESCING 后既有 lease 仍然有效，
 * 但 Registry 不再签发新 lease。</p>
 *
 * @since 0.11.0
 */
public interface PluginLease extends AutoCloseable {

    /** @return 此 lease 固定的不可变 snapshot */
    PluginSnapshot snapshot();

    /** 幂等释放引用。 */
    @Override
    void close();
}
