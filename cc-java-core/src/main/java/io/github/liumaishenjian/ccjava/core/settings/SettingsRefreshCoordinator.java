package io.github.liumaishenjian.ccjava.core.settings;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnostic;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticSeverity;
import io.github.liumaishenjian.ccjava.domain.settings.EffectiveSettings;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceId;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 将完整来源候选合并并以 compare-and-set 发布到 last-known-good 槽。
 *
 * <p>来源读取属于 Adapter；本协调器只接受已经原子成功的来源快照。取消、来源诊断、解析/合并
 * 失败以及 CAS 竞争均不修改 Store。成功候选以当前 revision 加一构建，只有比较成功才对读者可见。</p>
 *
 * @since 0.8.0
 */
public final class SettingsRefreshCoordinator {
    private static final SettingsSourceId REFRESH_SOURCE = new SettingsSourceId(
            io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceKind.DEFAULTS, "refresh");

    private final SettingsResolver resolver;
    private final SettingsSnapshotStore store;

    /**
     * 创建使用给定纯合并器和发布槽的刷新协调器。
     *
     * @param resolver 严格来源顺序的无副作用合并器
     * @param store last-known-good 发布槽
     */
    public SettingsRefreshCoordinator(SettingsResolver resolver, SettingsSnapshotStore store) {
        this.resolver = Objects.requireNonNull(resolver, "resolver 不能为空");
        this.store = Objects.requireNonNull(store, "store 不能为空");
    }

    /**
     * 尝试发布一次完整候选。
     *
     * @param snapshots 已成功读取、严格解析的全部候选来源，按固定低到高顺序排列
     * @param sourceDiagnostics 任意可选来源无效、缺失或读取失败的安全诊断
     * @param cancellationToken 刷新取消边界
     * @return 发布结果；任何失败都保留先前 last-known-good
     */
    public SettingsRefreshResult refresh(List<SettingsSourceSnapshot> snapshots,
                                         List<ConfigurationDiagnostic> sourceDiagnostics,
                                         CancellationToken cancellationToken) {
        return refresh(snapshots, sourceDiagnostics, cancellationToken, () -> { });
    }

    // 同包测试接缝在候选构建后制造竞争；生产调用不注入动作。
    SettingsRefreshResult refresh(List<SettingsSourceSnapshot> snapshots,
                                  List<ConfigurationDiagnostic> sourceDiagnostics,
                                  CancellationToken cancellationToken, Runnable beforePublish) {
        Objects.requireNonNull(beforePublish, "beforePublish 不能为空");
        snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots 不能为空"));
        sourceDiagnostics = List.copyOf(Objects.requireNonNull(sourceDiagnostics, "sourceDiagnostics 不能为空"));
        cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        Optional<EffectiveSettingsSnapshot> previous = store.current();
        if (cancellationToken.isCancellationRequested()) {
            return SettingsRefreshResult.notPublished(previous, List.of(diagnostic(ConfigurationDiagnosticCode.CANCELLED)));
        }
        if (!sourceDiagnostics.isEmpty()) {
            return SettingsRefreshResult.notPublished(previous, sourceDiagnostics);
        }
        SettingsResolution resolution = resolver.resolve(snapshots);
        if (resolution.effectiveSettings().isEmpty()) {
            return SettingsRefreshResult.notPublished(previous, resolution.diagnostics());
        }
        if (cancellationToken.isCancellationRequested()) {
            return SettingsRefreshResult.notPublished(previous, List.of(diagnostic(ConfigurationDiagnosticCode.CANCELLED)));
        }
        EffectiveSettings settings = resolution.effectiveSettings().orElseThrow();
        if (previous.isPresent() && previous.orElseThrow().revision() == Long.MAX_VALUE) {
            return SettingsRefreshResult.notPublished(previous, List.of(diagnostic(ConfigurationDiagnosticCode.REVISION_EXHAUSTED)));
        }
        beforePublish.run();
        if (cancellationToken.isCancellationRequested()) {
            return SettingsRefreshResult.notPublished(store.current(), List.of(diagnostic(ConfigurationDiagnosticCode.CANCELLED)));
        }
        long nextRevision = previous.map(snapshot -> snapshot.revision() + 1).orElse(1L);
        EffectiveSettingsSnapshot candidate = new EffectiveSettingsSnapshot(nextRevision, settings);
        Optional<Long> expectedRevision = previous.map(EffectiveSettingsSnapshot::revision);
        if (!store.replaceIfCurrent(expectedRevision, candidate)) {
            return SettingsRefreshResult.notPublished(store.current(), List.of(diagnostic(ConfigurationDiagnosticCode.CAS_CONFLICT)));
        }
        return SettingsRefreshResult.published(candidate);
    }

    private static ConfigurationDiagnostic diagnostic(ConfigurationDiagnosticCode code) {
        return new ConfigurationDiagnostic(REFRESH_SOURCE, code, ConfigurationDiagnosticSeverity.ERROR, Optional.empty());
    }
}
