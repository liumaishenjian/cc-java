package io.github.liumaishenjian.ccjava.core.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 线程安全的内存 SessionIndex，适合测试与小规模进程投影。 */
public final class InMemorySessionIndex implements SessionIndex {
    /** 创建空的进程内派生索引。 */
    public InMemorySessionIndex() { }

    private final Map<String, SessionIndexEntry> entries = new LinkedHashMap<>();
    @Override public synchronized void upsert(SessionIndexEntry entry) { entries.put(entry.sessionId(), entry); }
    @Override public synchronized void remove(String sessionId) { entries.remove(sessionId); }
    @Override public synchronized List<SessionIndexEntry> list(int offset, int limit) { validate(offset, limit); return sorted().stream().skip(offset).limit(limit).toList(); }
    @Override public synchronized List<SessionIndexEntry> search(String query, int limit) { validate(0, limit); String normalized = Objects.requireNonNull(query, "query 不能为空").toLowerCase(Locale.ROOT); if (normalized.length() > 256) throw new IllegalArgumentException("query 超限"); return sorted().stream().filter(e -> e.sessionId().toLowerCase(Locale.ROOT).contains(normalized) || e.displayName().toLowerCase(Locale.ROOT).contains(normalized)).limit(limit).toList(); }
    @Override public synchronized Optional<SessionIndexEntry> find(String sessionId) { return Optional.ofNullable(entries.get(sessionId)); }
    @Override public synchronized void rebuild(Iterable<SessionIndexEntry> source) { entries.clear(); for (SessionIndexEntry entry : source) upsert(entry); }
    private List<SessionIndexEntry> sorted() { ArrayList<SessionIndexEntry> result = new ArrayList<>(entries.values()); result.sort(Comparator.comparing(SessionIndexEntry::updatedAt).reversed().thenComparing(SessionIndexEntry::sessionId)); return result; }
    private static void validate(int offset, int limit) { if (offset < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("分页参数非法"); }
}
