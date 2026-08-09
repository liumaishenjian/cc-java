package io.github.liumaishenjian.ccjava.core.plugin;

import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 在 Run 边界捕获可信 Plugin snapshot lease，并以 exactly-once 逆序释放。
 *
 * <p>本协调器不发现 Plugin、不创建 Tool、不执行 Hook，也不修改 Registry active generation。
 * Run 开始时只对当时 ACTIVE 且仍通过 Registry trust Gate 的 snapshot 签发 lease；QUIESCING 后
 * 不会签发新 lease，但既有 Run 保持固定 generation。终态清理按捕获顺序逆序关闭，覆盖成功、
 * 拒绝、取消和失败；Resume/Fork 不调用 {@link #openRun(RunId)}，因此不会自动重放贡献。</p>
 *
 * @since 0.11.0
 */
public final class PluginRunCoordinator {
    private static final PluginRunCoordinator DISABLED = new PluginRunCoordinator();

    private final PluginRegistry registry;
    private final ConcurrentMap<RunId, RunLease> runs = new ConcurrentHashMap<>();
    private final boolean enabled;

    /** 创建对当前 Registry 签发 Run lease 的协调器。 */
    public PluginRunCoordinator(PluginRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        enabled = true;
    }

    private PluginRunCoordinator() {
        registry = null;
        enabled = false;
    }

    /** @return 不捕获任何 Plugin 的共享兼容实现 */
    public static PluginRunCoordinator disabled() {
        return DISABLED;
    }

    /**
     * 原子捕获 Run 启动时的可信 ACTIVE snapshots。
     *
     * @param runId 新 Run
     * @throws IllegalStateException 同一 Run 重复打开或捕获中发生 generation 漂移
     */
    public void openRun(RunId runId) {
        Objects.requireNonNull(runId, "runId 不能为空");
        if (!enabled) return;
        List<PluginLease> leases = new ArrayList<>();
        try {
            for (var snapshot : registry.activeSnapshot().snapshots()) {
                if (!registry.isTrusted(snapshot)) {
                    throw new IllegalStateException("Plugin snapshot 未通过 trust Gate");
                }
                PluginLease lease = registry.acquire(snapshot.manifest().id())
                        .orElseThrow(() -> new IllegalStateException("Plugin generation 已漂移"));
                if (!lease.snapshot().fingerprint().equals(snapshot.fingerprint())) {
                    lease.close();
                    throw new IllegalStateException("Plugin generation 已漂移");
                }
                leases.add(lease);
            }
            RunLease captured = new RunLease(leases);
            if (runs.putIfAbsent(runId, captured) != null) {
                captured.close();
                throw new IllegalStateException("Run 已捕获 Plugin lease");
            }
        } catch (RuntimeException failure) {
            closeReverse(leases);
            throw failure;
        }
    }

    /** @return 当前 Run 固定的 Plugin ID 与 tree/config digest，不含路径或配置正文 */
    public Map<PluginId, String> fingerprints(RunId runId) {
        RunLease lease = runs.get(Objects.requireNonNull(runId, "runId 不能为空"));
        return lease == null ? Map.of() : lease.fingerprints();
    }

    /** 在 Run 唯一终态逆序、幂等释放全部 snapshot lease。 */
    public void closeRun(RunId runId) {
        RunLease lease = runs.remove(Objects.requireNonNull(runId, "runId 不能为空"));
        if (lease != null) lease.close();
    }

    private static void closeReverse(List<? extends AutoCloseable> closeables) {
        for (int index = closeables.size() - 1; index >= 0; index--) {
            try {
                closeables.get(index).close();
            } catch (Exception ignored) {
                // 清理继续尝试全部 lease；异常不得泄漏 snapshot 内容或路径。
            }
        }
    }

    private static final class RunLease implements AutoCloseable {
        private List<PluginLease> leases;

        private RunLease(List<PluginLease> leases) {
            this.leases = new ArrayList<>(leases);
        }

        private synchronized Map<PluginId, String> fingerprints() {
            Map<PluginId, String> values = new LinkedHashMap<>();
            for (PluginLease lease : leases) {
                var fingerprint = lease.snapshot().fingerprint();
                values.put(fingerprint.pluginId(), fingerprint.treeDigest());
            }
            return Collections.unmodifiableMap(values);
        }

        @Override
        public synchronized void close() {
            closeReverse(leases);
            leases = List.of();
        }
    }
}
