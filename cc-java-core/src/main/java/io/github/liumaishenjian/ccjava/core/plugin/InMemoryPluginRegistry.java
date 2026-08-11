package io.github.liumaishenjian.ccjava.core.plugin;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginId;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginRegistryState;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshotSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 线程安全、进程内的 Plugin generation Registry 与引用计数实现。
 *
 * <p>每个 Plugin ID 只有一个 active generation，但旧 generation 会保留在 retired 集合直到其
 * Session lease 归零并由 {@link #drainRetiredReady()} 恰好消费一次。所有状态与 activation
 * prepare/commit/rollback 在同一 monitor 下完成；本类型不声称崩溃恢复或跨进程管理。</p>
 *
 * @since 0.11.0
 */
public final class InMemoryPluginRegistry implements PluginRegistry {
    private final PluginTrustGate trustGate;
    private final Map<PluginId, Entry> active = new LinkedHashMap<>();
    private final List<Entry> retired = new ArrayList<>();
    private long nextGeneration = 1;
    private long revision;

    /**
     * 创建使用给定信任 Gate 的进程内 Registry。
     *
     * @param trustGate 激活每个 generation 前必须通过的信任判定
     */
    public InMemoryPluginRegistry(PluginTrustGate trustGate) {
        this.trustGate = Objects.requireNonNull(trustGate, "trustGate 不能为空");
    }

    @Override
    public boolean isTrusted(PluginSnapshot snapshot) {
        snapshot = Objects.requireNonNull(snapshot, "snapshot 不能为空");
        return trustGate.isTrusted(snapshot.fingerprint());
    }

    @Override
    public synchronized PluginActivation prepareActivation(PluginSnapshot snapshot) {
        snapshot = Objects.requireNonNull(snapshot, "snapshot 不能为空");
        if (!trustGate.isTrusted(snapshot.fingerprint())) {
            throw new IllegalArgumentException("Plugin fingerprint 未获信任");
        }
        PluginId id = snapshot.manifest().id();
        Entry previous = active.get(id);
        if (previous != null && previous.state != PluginRegistryState.ACTIVE) {
            throw new IllegalStateException("Plugin 正在卸载");
        }
        return new Activation(this, snapshot, previous, revision);
    }

    @Override
    public synchronized PluginSnapshotSet activeSnapshot() {
        return new PluginSnapshotSet(active.values().stream()
                .filter(entry -> entry.state == PluginRegistryState.ACTIVE)
                .map(entry -> entry.snapshot).toList());
    }

    @Override
    public synchronized Optional<PluginLease> acquire(PluginId pluginId) {
        Entry entry = active.get(Objects.requireNonNull(pluginId, "pluginId 不能为空"));
        if (entry == null || entry.state != PluginRegistryState.ACTIVE) return Optional.empty();
        entry.leases++;
        return Optional.of(new Lease(this, entry));
    }

    @Override
    public synchronized boolean beginQuiescing(PluginId pluginId) {
        Entry entry = active.get(Objects.requireNonNull(pluginId, "pluginId 不能为空"));
        if (entry == null || entry.state != PluginRegistryState.ACTIVE) return false;
        entry.state = PluginRegistryState.QUIESCING;
        revision++;
        return true;
    }

    @Override
    public synchronized Optional<PluginSnapshot> completeRemoval(PluginId pluginId) {
        Entry entry = active.get(Objects.requireNonNull(pluginId, "pluginId 不能为空"));
        if (entry == null || entry.state != PluginRegistryState.QUIESCING || entry.leases != 0) {
            return Optional.empty();
        }
        entry.state = PluginRegistryState.REMOVED;
        revision++;
        return Optional.of(entry.snapshot);
    }

    @Override
    public synchronized void markDeleted(PluginId pluginId) {
        Entry entry = active.get(Objects.requireNonNull(pluginId, "pluginId 不能为空"));
        if (entry == null || entry.state != PluginRegistryState.REMOVED || entry.leases != 0) {
            throw new IllegalStateException("Plugin 尚不可删除");
        }
        active.remove(pluginId);
        revision++;
    }

    @Override
    public synchronized void markTombstoned(PluginId pluginId) {
        Entry entry = active.get(Objects.requireNonNull(pluginId, "pluginId 不能为空"));
        if (entry == null || entry.state != PluginRegistryState.REMOVED || entry.leases != 0) {
            throw new IllegalStateException("Plugin 尚不可 tombstone");
        }
        entry.state = PluginRegistryState.TOMBSTONED;
        revision++;
    }

    @Override
    public synchronized Optional<PluginRegistryState> state(PluginId pluginId) {
        Entry entry = active.get(Objects.requireNonNull(pluginId, "pluginId 不能为空"));
        return entry == null ? Optional.empty() : Optional.of(entry.state);
    }

    @Override
    public synchronized int leaseCount(PluginId pluginId) {
        Entry entry = active.get(Objects.requireNonNull(pluginId, "pluginId 不能为空"));
        return entry == null ? 0 : entry.leases;
    }

    @Override
    public synchronized List<RetiredPluginGeneration> drainRetiredReady() {
        var ready = new ArrayList<RetiredPluginGeneration>();
        var iterator = retired.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.leases == 0 && !entry.recoveryClaimed) {
                entry.recoveryClaimed = true;
                ready.add(new RetiredPluginGeneration(entry.snapshot, entry.generationId));
                iterator.remove();
            }
        }
        return List.copyOf(ready);
    }

    private synchronized void commit(Activation activation) {
        if (activation.finished || activation.baseRevision != revision
                || active.get(activation.pluginId) != activation.previous) {
            throw new IllegalStateException("Plugin activation state 已变化");
        }
        if (!trustGate.isTrusted(activation.candidate.fingerprint())) {
            throw new IllegalStateException("Plugin trust 已变化");
        }
        Entry replacement = new Entry(activation.candidate, nextGeneration++);
        if (activation.previous != null) {
            activation.previous.state = PluginRegistryState.REMOVED;
            retired.add(activation.previous);
        }
        active.put(activation.pluginId, replacement);
        activation.replacement = replacement;
        activation.committed = true;
        activation.finished = true;
        revision++;
    }

    private synchronized void rollback(Activation activation) {
        if (activation.rolledBack) return;
        if (!activation.committed) {
            activation.finished = true;
            activation.rolledBack = true;
            return;
        }
        if (active.get(activation.pluginId) != activation.replacement) {
            throw new IllegalStateException("Plugin activation 无法安全回滚");
        }
        retired.remove(activation.previous);
        if (activation.previous == null) active.remove(activation.pluginId);
        else {
            activation.previous.state = PluginRegistryState.ACTIVE;
            active.put(activation.pluginId, activation.previous);
        }
        activation.replacement.state = PluginRegistryState.REMOVED;
        if (activation.replacement.leases != 0) {
            throw new IllegalStateException("已签发新 generation lease，无法回滚");
        }
        activation.committed = false;
        activation.rolledBack = true;
        revision++;
    }

    private synchronized void release(Entry entry) {
        if (entry.leases <= 0) throw new IllegalStateException("Plugin lease 计数不一致");
        entry.leases--;
    }

    private static final class Entry {
        private final PluginSnapshot snapshot;
        private final long generationId;
        private PluginRegistryState state = PluginRegistryState.ACTIVE;
        private int leases;
        private boolean recoveryClaimed;
        private Entry(PluginSnapshot snapshot, long generationId) {
            this.snapshot = snapshot;
            this.generationId = generationId;
        }
    }

    private static final class Activation implements PluginActivation {
        private final InMemoryPluginRegistry owner;
        private final PluginSnapshot candidate;
        private final PluginId pluginId;
        private final Entry previous;
        private final long baseRevision;
        private Entry replacement;
        private boolean committed;
        private boolean finished;
        private boolean rolledBack;
        private Activation(InMemoryPluginRegistry owner, PluginSnapshot candidate,
                Entry previous, long baseRevision) {
            this.owner = owner;
            this.candidate = candidate;
            this.pluginId = candidate.manifest().id();
            this.previous = previous;
            this.baseRevision = baseRevision;
        }
        @Override public PluginSnapshot candidate() { return candidate; }
        @Override public void commit() { owner.commit(this); }
        @Override public void rollback() { owner.rollback(this); }
        @Override public void close() { if (!finished) rollback(); }
    }

    private static final class Lease implements PluginLease {
        private final InMemoryPluginRegistry owner;
        private final Entry entry;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(InMemoryPluginRegistry owner, Entry entry) {
            this.owner = owner;
            this.entry = entry;
        }
        @Override public PluginSnapshot snapshot() { return entry.snapshot; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) owner.release(entry);
        }
    }
}
