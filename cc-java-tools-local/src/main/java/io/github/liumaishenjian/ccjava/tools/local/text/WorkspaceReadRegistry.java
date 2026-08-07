package io.github.liumaishenjian.ccjava.tools.local.text;

import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 单个 Workspace 内、按 Session 隔离的有界 Read 证据登记表。
 *
 * <p>它回答两个问题：</p>
 * <ul>
 *   <li>同一 Session 是否已经读过某个路径的某个行区间，且文件身份至今未变（可以返回
 *       轻量“未变化”结果，不必重复整页正文）；</li>
 *   <li>某次写入之前，是否存在覆盖待修改区域的可信 Read 证据。</li>
 * </ul>
 *
 * <p>登记表刻意只依赖 {@link SessionId} 与协议路径，不接触 Provider、Spring、Reactor、
 * 持久化框架或绝对路径；它是纯进程内缓存，Session Resume 或进程重启后自然为空，
 * 此时调用方必须退化为重新读取，而不是拒绝工作。每个 Session 的条目数与 Session 数量
 * 都有固定上限，超限时按最近最少使用淘汰，因此长时间运行不会无界增长。</p>
 *
 * <p>该类型是线程安全的：全部状态变更都在同一把内部锁下完成，因此并发 Tool 调用得到的
 * 是确定性的“要么看到旧证据、要么看到新证据”，不会看到半更新状态。</p>
 *
 * @since 0.8.0
 */
public final class WorkspaceReadRegistry {

    /** 单个 Session 最多保留的路径证据数。 */
    public static final int MAX_ENTRIES_PER_SESSION = 256;

    /** 最多保留的 Session 数。 */
    public static final int MAX_SESSIONS = 32;

    private final Object lock = new Object();
    private final LinkedHashMap<String, LinkedHashMap<String, ReadEvidence>> sessions =
            new LinkedHashMap<>(16, 0.75f, true);

    /**
     * 记录一次成功 Read 或写入后的权威内容证据。
     *
     * @param sessionId 所属 Session
     * @param evidence 已完成读取或写入的证据
     */
    public void record(SessionId sessionId, ReadEvidence evidence) {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Objects.requireNonNull(evidence, "evidence 不能为空");
        synchronized (lock) {
            LinkedHashMap<String, ReadEvidence> entries = sessions.remove(sessionId.value());
            if (entries == null) {
                entries = new LinkedHashMap<>(16, 0.75f, true);
            }
            entries.remove(evidence.protocolPath());
            entries.put(evidence.protocolPath(), evidence);
            evict(entries, MAX_ENTRIES_PER_SESSION);
            sessions.put(sessionId.value(), entries);
            evict(sessions, MAX_SESSIONS);
        }
    }

    /**
     * 查找同 Session 同路径的最近一次证据。
     *
     * @param sessionId 所属 Session
     * @param protocolPath 协议路径
     * @return 存在时返回证据
     */
    public Optional<ReadEvidence> find(SessionId sessionId, String protocolPath) {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Objects.requireNonNull(protocolPath, "protocolPath 不能为空");
        synchronized (lock) {
            LinkedHashMap<String, ReadEvidence> entries = sessions.get(sessionId.value());
            if (entries == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(entries.get(protocolPath));
        }
    }

    /**
     * 在文件被修改后作废该路径在所有 Session 中的证据。
     *
     * <p>写工具在成功落盘后必须先作废、再登记新的权威证据，使“同一 Session 连续编辑”
     * 依赖的是刚写入的已知内容，而不是过期区间。</p>
     *
     * @param protocolPath 协议路径
     */
    public void invalidate(String protocolPath) {
        Objects.requireNonNull(protocolPath, "protocolPath 不能为空");
        synchronized (lock) {
            for (LinkedHashMap<String, ReadEvidence> entries : sessions.values()) {
                entries.remove(protocolPath);
            }
        }
    }

    /**
     * 返回当前保留的证据总数，供测试确认有界淘汰确实生效。
     *
     * @return 全部 Session 的条目总数
     */
    public int size() {
        synchronized (lock) {
            int total = 0;
            for (LinkedHashMap<String, ReadEvidence> entries : sessions.values()) {
                total += entries.size();
            }
            return total;
        }
    }

    private static <K, V> void evict(LinkedHashMap<K, V> entries, int maximum) {
        Iterator<Map.Entry<K, V>> iterator = entries.entrySet().iterator();
        while (entries.size() > maximum && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }
}
