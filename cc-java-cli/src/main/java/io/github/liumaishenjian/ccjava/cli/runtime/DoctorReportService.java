package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.domain.ContextReduction;
import io.github.liumaishenjian.ccjava.domain.ContextUsageView;
import io.github.liumaishenjian.ccjava.domain.command.SessionCommandEvent;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnostic;
import io.github.liumaishenjian.ccjava.domain.settings.EffectiveSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将已发布的 Headless 状态映射为只读、隐私安全的 doctor 报告。
 *
 * <p>本服务不刷新 Settings 或 Instructions，不读取文件，也不执行模型或 Tool。所有输入均为
 * 已发布内存快照，任何 mapper 失败由调用方收敛为固定终态。</p>
 *
 * @since 0.8.0
 */
public final class DoctorReportService {
    private final HeadlessRuntimeSession runtime;

    /**
     * 绑定单个 Headless Session 的只读 doctor mapper。
     *
     * @param runtime 仅提供已发布内存快照的 Runtime
     */
    public DoctorReportService(HeadlessRuntimeSession runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为空");
    }

    /**
     * 生成一份不触发外部操作的 doctor payload。
     *
     * @return 仅含安全 ID、固定 code、白名单数值和枚举的报告
     */
    public SessionCommandEvent.DoctorPayload report() {
        HeadlessRuntimeSession.DoctorSnapshot snapshot = runtime.doctorSnapshot();
        List<SessionCommandEvent.DoctorEntry> entries = new ArrayList<>();
        snapshot.settings().ifPresent(settings -> appendSettings(entries, settings.settings()));
        entries.add(new SessionCommandEvent.DoctorEntry(
                "GOVERNANCE", "MANAGED", "managed-policy",
                snapshot.governance().status().name(),
                snapshot.governance().status()
                        == io.github.liumaishenjian.ccjava.core.governance.ManagedPolicyStatus.FAIL_CLOSED
                        ? "ERROR" : "INFO"));
        snapshot.governance().gates().forEach(gate -> entries.add(
                new SessionCommandEvent.DoctorEntry("FEATURE_GATE", gate.stability().name(), gate.id(),
                        gate.enabled() ? "ENABLED" : "DISABLED", "INFO")));
        snapshot.instructions().ifPresent(instructions -> {
            instructions.sources().forEach(source -> entries.add(new SessionCommandEvent.DoctorEntry(
                    "INSTRUCTIONS", source.sourceKind(), source.safeId(), "PUBLISHED", "INFO")));
            instructions.diagnostics().forEach(diagnostic -> entries.add(new SessionCommandEvent.DoctorEntry(
                    "INSTRUCTIONS", diagnostic.sourceKind(), diagnostic.safeId(), diagnostic.code(), diagnostic.severity())));
        });
        return new SessionCommandEvent.DoctorPayload(snapshot.settings().isPresent(),
                snapshot.settings().map(value -> value.revision()).orElse(0L),
                snapshot.instructions().map(value -> value.sources().size()).orElse(0),
                snapshot.contextUsage().isPresent(), snapshot.activeRun(), entries);
    }

    /**
     * 将 S07 view 映射为没有 Projection 内容的数值/枚举 payload。
     *
     * @param view 已发布的 Context Usage View
     * @return 不含 Canonical 内容或记忆正文的安全投影
     */
    public static SessionCommandEvent.ContextPayload context(ContextUsageView view) {
        Objects.requireNonNull(view, "view 不能为空");
        return new SessionCommandEvent.ContextPayload(
                view.usage().systemTokens(), view.usage().transcriptTokens(), view.usage().toolTokens(),
                view.usage().memoryTokens(), view.usage().totalTokens(), view.availableInputTokens(), view.freeTokens(),
                view.overflowTokens(), view.sourceRevision(), view.usage().estimateKind().name(), view.status().name(),
                view.appliedReductions().stream().map(ContextReduction::strategy).map(Enum::name).toList(),
                view.reasonCodes().stream().map(Enum::name).toList(), view.modelRequestAttempts());
    }

    private static void appendSettings(List<SessionCommandEvent.DoctorEntry> entries, EffectiveSettings settings) {
        settings.diagnostics().forEach(diagnostic -> entries.add(settingDiagnostic(diagnostic)));
        settings.modelName().ifPresent(value -> entries.add(settingProvenance("MODEL_NAME", value.provenance())));
        settings.permissionMode().ifPresent(value -> entries.add(settingProvenance("PERMISSION_MODE", value.provenance())));
        settings.diagnosticsVerbosity().ifPresent(value -> entries.add(settingProvenance("DIAGNOSTICS_VERBOSITY", value.provenance())));
    }

    private static SessionCommandEvent.DoctorEntry settingDiagnostic(ConfigurationDiagnostic diagnostic) {
        return new SessionCommandEvent.DoctorEntry("SETTINGS", diagnostic.sourceId().kind().name(),
                diagnostic.sourceId().safeId(), diagnostic.code().name(), diagnostic.severity().name());
    }

    private static SessionCommandEvent.DoctorEntry settingProvenance(
            String code, io.github.liumaishenjian.ccjava.domain.settings.SettingProvenance provenance) {
        return new SessionCommandEvent.DoctorEntry("SETTINGS", provenance.sourceId().kind().name(),
                provenance.sourceId().safeId(), code, provenance.validationStatus().name());
    }
}
