package io.github.liumaishenjian.ccjava.core.session;

import java.util.List;
import java.util.Optional;

/**
 * 大量 Session 的可重建查询投影端口；Canonical JSONL 始终是事实源。
 *
 * @since 0.1.0
 */
public interface SessionIndex {
    /**
     * 新增或替换一条 metadata projection。
     *
     * @param entry 替换或新增的 metadata projection
     */
    void upsert(SessionIndexEntry entry);

    /**
     * 删除一条派生索引记录，但不删除 canonical Session。
     *
     * @param sessionId 要删除的派生条目
     */
    void remove(String sessionId);

    /**
     * 按稳定更新时间逆序列出。
     *
     * @param offset 零基偏移
     * @param limit 最大返回数
     * @return 稳定分页 entries
     */
    List<SessionIndexEntry> list(int offset, int limit);

    /**
     * 按安全 display name/session ID 搜索。
     *
     * @param query 有界查询文本
     * @param limit 最大返回数
     * @return 匹配 entries
     */
    List<SessionIndexEntry> search(String query, int limit);

    /**
     * 按 ID 查询。
     *
     * @param sessionId Session identity
     * @return 匹配 entry
     */
    Optional<SessionIndexEntry> find(String sessionId);

    /**
     * 从 canonical metadata 完整重建可丢弃的索引投影。
     *
     * @param entries 从 canonical metadata 枚举出的完整投影
     */
    void rebuild(Iterable<SessionIndexEntry> entries);
}
