package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.cli.governance.ManagedGovernance;
import io.github.liumaishenjian.ccjava.cli.session.SessionLifecycleService;
import io.github.liumaishenjian.ccjava.core.session.RetentionAction;
import io.github.liumaishenjian.ccjava.core.session.SessionIndexEntry;
import io.github.liumaishenjian.ccjava.domain.governance.FeatureStability;
import io.github.liumaishenjian.ccjava.sdk.AgentControlApi;
import java.util.List;
import java.util.Objects;

/** Headless production control API 的 Session/Governance 适配器。 */
public final class ProductionControlApi implements AgentControlApi {
    private final SessionLifecycleService sessions;
    private final ManagedGovernance governance;

    /**
     * 创建绑定唯一 Session lifecycle 与 Managed Governance 的控制 API。
     *
     * @param sessions Session 导出、保留、索引与迁移服务
     * @param governance 机器策略与 Feature Gate 投影
     */
    public ProductionControlApi(SessionLifecycleService sessions, ManagedGovernance governance) {
        this.sessions = Objects.requireNonNull(sessions);
        this.governance = Objects.requireNonNull(governance);
    }
    @Override public byte[] exportSession(String id,
            boolean include, boolean redacted, boolean confirmed) {
        return sessions.export(id, include, redacted, confirmed);
    }
    @Override public ControlResult retainSession(String id,
            RetentionAction action, boolean first, boolean second) {
        var result = sessions.retain(id, action, first, second);
        return new ControlResult(result.success(), result.status());
    }
    @Override public List<SessionIndexEntry> listSessions(int offset, int limit) {
        return sessions.list(offset, limit);
    }
    @Override public List<SessionIndexEntry> searchSessions(String query, int limit) {
        return sessions.search(query, limit);
    }
    @Override public MigrationControlResult migrateSession(
            String sourceFile, String targetFile, int fromMajor, int toMajor) {
        java.nio.file.Path root = sessions.root();
        java.nio.file.Path source = controlledChild(root, sourceFile);
        java.nio.file.Path target = controlledChild(root, targetFile);
        var result = sessions.migrate(source, target, fromMajor, toMajor, line -> line);
        return new MigrationControlResult(result.success(), result.status(), result.records());
    }
    private static java.nio.file.Path controlledChild(java.nio.file.Path root, String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("migration file 非法");
        java.nio.file.Path value = root.resolve(name).normalize();
        if (!root.equals(value.getParent())) throw new IllegalArgumentException("migration 越界");
        return value;
    }
    @Override public GovernanceView governance() {
        var snapshot = governance.snapshot();
        return new GovernanceView(snapshot.status().name(), snapshot.usingLkg(),
                snapshot.gates().stream().filter(g -> g.enabled() && g.stability() == FeatureStability.STABLE)
                        .map(g -> g.id()).toList(),
                snapshot.gates().stream().filter(g -> g.enabled() && g.stability() == FeatureStability.EXPERIMENTAL)
                        .map(g -> g.id()).toList());
    }
}
