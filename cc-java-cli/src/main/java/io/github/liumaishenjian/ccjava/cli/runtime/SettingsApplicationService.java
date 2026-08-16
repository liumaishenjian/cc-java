package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.cli.settings.SettingsFixedSourceLoader;
import io.github.liumaishenjian.ccjava.cli.settings.SettingsV1SourceParser;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ToolRegistry;
import io.github.liumaishenjian.ccjava.core.settings.EffectiveSettingsSnapshot;
import io.github.liumaishenjian.ccjava.core.settings.RuntimeSettingsApplier;
import io.github.liumaishenjian.ccjava.core.settings.SettingsResolution;
import io.github.liumaishenjian.ccjava.core.settings.SettingsResolver;
import io.github.liumaishenjian.ccjava.core.settings.SettingsSnapshotStore;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewer;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnostic;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticSeverity;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredSettings;
import io.github.liumaishenjian.ccjava.domain.settings.RuntimeConfiguration;
import io.github.liumaishenjian.ccjava.domain.settings.RuntimeSettingsApplyResult;
import io.github.liumaishenjian.ccjava.domain.settings.RuntimeSettingsDiagnostic;
import io.github.liumaishenjian.ccjava.domain.settings.RuntimeSettingsDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsRevision;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceId;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceKind;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceSnapshot;
import io.github.liumaishenjian.ccjava.domain.settings.SessionSettingsPatch;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 将固定文件和短生命周期 overlay 以单一 idle 事务应用到 Headless Runtime。
 *
 * <p>固定来源 I/O、解析和候选 Scope 构建都在 Headless 生命周期锁外完成。仅最终 LKG CAS 与
 * Scope 交换在生命周期锁内执行；活动、关闭、取消、冲突或内部失败均保留原有配对状态。</p>
 *
 * @since 0.8.0
 */
public final class SettingsApplicationService {
    private static final SettingsSourceId DEFAULTS_ID = new SettingsSourceId(SettingsSourceKind.DEFAULTS, "defaults");
    private static final SettingsSourceId APPLICATION_ID = new SettingsSourceId(SettingsSourceKind.DEFAULTS, "application");
    private static final SettingsRevision DEFAULTS_REVISION = new SettingsRevision("0".repeat(64));

    private final Object applicationMonitor = new Object();
    private final HeadlessRuntimeSession runtime;
    private final SettingsFixedSourceLoader fixedLoader;
    private final SettingsResolver resolver;
    private final SettingsSnapshotStore store;
    private final RuntimeSettingsApplier applier;
    private SettingsSourceSnapshot user;
    private SettingsSourceSnapshot projectShared;
    private SettingsSourceSnapshot projectLocal;
    private SettingsSourceSnapshot session;
    private SettingsSourceSnapshot cli;
    private ApprovalReviewer sessionReviewer = ApprovalReviewer.USER;

    /**
     * 以已验证固定来源 Adapter 创建应用服务。
     *
     * @param runtime 生命周期与 Scope 所有者
     * @param fixedLoader 固定文件来源加载器
     * @param registry 已注册 builtin Tool
     */
    public SettingsApplicationService(HeadlessRuntimeSession runtime, SettingsFixedSourceLoader fixedLoader,
                                      ToolRegistry registry) {
        this(runtime, fixedLoader, new SettingsResolver(), new SettingsSnapshotStore(),
                new RuntimeSettingsApplier(runtime.runtimeConfiguration(),
                        runtime.runtimeConfiguration().modelName().stream().toList(), registry, Map.of()));
    }

    /**
     * 创建生产固定来源接线，调用方提供一次解析后的可信 user home。
     *
     * @param runtime 生命周期与 Scope 所有者
     * @param userHome Composition Root 已解析一次的用户目录
     * @return 固定来源应用服务
     */
    public static SettingsApplicationService production(HeadlessRuntimeSession runtime, Path userHome) {
        Objects.requireNonNull(runtime, "runtime 不能为空");
        SettingsV1SourceParser parser = new SettingsV1SourceParser(runtime.builtinToolRegistry().definitions().stream()
                .filter(definition -> definition.source() == io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN)
                .map(io.github.liumaishenjian.ccjava.domain.ToolDefinition::name)
                .collect(java.util.stream.Collectors.toSet()));
        return new SettingsApplicationService(runtime,
                new SettingsFixedSourceLoader(userHome, runtime.workspaceGuard(), parser), runtime.builtinToolRegistry());
    }

    SettingsApplicationService(HeadlessRuntimeSession runtime, SettingsFixedSourceLoader fixedLoader,
                               SettingsResolver resolver, SettingsSnapshotStore store, RuntimeSettingsApplier applier) {
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为空");
        this.fixedLoader = Objects.requireNonNull(fixedLoader, "fixedLoader 不能为空");
        this.resolver = Objects.requireNonNull(resolver, "resolver 不能为空");
        this.store = Objects.requireNonNull(store, "store 不能为空");
        this.applier = Objects.requireNonNull(applier, "applier 不能为空");
    }

    /**
     * 显式重读固定来源；活动 Run 在读取前立即返回，绝不触发 loader。
     *
     * @param cancellationToken 本次读取取消边界
     * @return 完整发布结果或保留 LKG 的安全诊断
     */
    public SettingsApplicationResult refresh(CancellationToken cancellationToken) {
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        if (cancellationToken.isCancellationRequested()) return rejected(ConfigurationDiagnosticCode.CANCELLED);
        if (runtime.isClosedOrActiveForSettings()) return rejected(RuntimeSettingsDiagnosticCode.ACTIVE_RUN);
        synchronized (applicationMonitor) {
            if (runtime.isClosedOrActiveForSettings()) return rejected(RuntimeSettingsDiagnosticCode.ACTIVE_RUN);
            Candidate fixed = loadFixed(cancellationToken);
            if (!fixed.diagnostics().isEmpty()) return rejected(fixed.diagnostics());
            return publish(fixed.user(), fixed.projectShared(), fixed.projectLocal(), session, cli, sessionReviewer,
                    cancellationToken);
        }
    }

    /**
     * 替换 Session 内存 overlay；空值移除该 overlay，且不读取固定文件。
     *
     * @param overlay 已验证 overlay 或空值
     * @param cancellationToken 发布取消边界
     * @return 完整发布结果或保留 LKG 的安全诊断
     */
    public SettingsApplicationResult replaceSessionOverlay(Optional<DeclaredSettings> overlay, CancellationToken cancellationToken) {
        return replaceOverlay(SettingsSourceKind.SESSION, overlay, cancellationToken);
    }

    /**
     * 在保留全部现有 Session 字段的前提下应用受限标量补丁。
     *
     * <p>该操作不读取固定来源，也不写入文件、JSONL 或 Checkpoint。候选 overlay 只有在既有
     * LKG/Scope 配对事务成功后才成为当前值，因此取消、活动 Run、验证失败和 CAS 冲突均保留旧值。</p>
     *
     * @param patch 只能是模型、PermissionMode 或 PermissionSelection 的封闭更新
     * @param cancellationToken 发布取消边界
     * @return 完整发布结果或保留 LKG 的安全诊断
     */
    public SettingsApplicationResult patchSessionOverlay(SessionSettingsPatch patch, CancellationToken cancellationToken) {
        Objects.requireNonNull(patch, "patch 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        if (cancellationToken.isCancellationRequested()) return rejected(ConfigurationDiagnosticCode.CANCELLED);
        if (runtime.isClosedOrActiveForSettings()) return rejected(RuntimeSettingsDiagnosticCode.ACTIVE_RUN);
        synchronized (applicationMonitor) {
            if (runtime.isClosedOrActiveForSettings()) return rejected(RuntimeSettingsDiagnosticCode.ACTIVE_RUN);
            DeclaredSettings base = session == null ? emptySettings() : session.declaredValues();
            ApprovalReviewer candidateReviewer = switch (patch) {
                case SessionSettingsPatch.PermissionSelectionChange selection -> selection.value().reviewer();
                case SessionSettingsPatch.PermissionModeChange ignored -> ApprovalReviewer.USER;
                case SessionSettingsPatch.ModelName ignored -> sessionReviewer;
            };
            DeclaredSettings patched = switch (patch) {
                case SessionSettingsPatch.ModelName model -> new DeclaredSettings(Optional.of(model.value()),
                        base.permissionMode(), base.permissionRules(), base.enabledTools(), base.toolConfigurations(),
                        base.compactInstructions(), base.diagnosticsVerbosity());
                case SessionSettingsPatch.PermissionModeChange mode -> new DeclaredSettings(base.modelName(),
                        Optional.of(mode.value().name()), base.permissionRules(), base.enabledTools(),
                        base.toolConfigurations(), base.compactInstructions(), base.diagnosticsVerbosity());
                case SessionSettingsPatch.PermissionSelectionChange selection -> new DeclaredSettings(base.modelName(),
                        Optional.of(selection.value().mode().name()), base.permissionRules(), base.enabledTools(),
                        base.toolConfigurations(), base.compactInstructions(), base.diagnosticsVerbosity());
            };
            return publish(user, projectShared, projectLocal, snapshot(SettingsSourceKind.SESSION, patched), cli,
                    candidateReviewer, cancellationToken);
        }
    }

    /**
     * 替换 CLI 内存 overlay；空值移除该 overlay，且不读取固定文件。
     *
     * @param overlay 已验证 overlay 或空值
     * @param cancellationToken 发布取消边界
     * @return 完整发布结果或保留 LKG 的安全诊断
     */
    public SettingsApplicationResult replaceCliOverlay(Optional<DeclaredSettings> overlay, CancellationToken cancellationToken) {
        return replaceOverlay(SettingsSourceKind.CLI, overlay, cancellationToken);
    }

    /**
     * 返回当前完整 LKG，不暴露 Scope、路径、正文或凭证。
     *
     * @return 首次成功前为空的完整 LKG
     */
    public Optional<EffectiveSettingsSnapshot> current() {
        return store.current();
    }

    private SettingsApplicationResult replaceOverlay(SettingsSourceKind kind, Optional<DeclaredSettings> overlay,
                                                     CancellationToken token) {
        Objects.requireNonNull(overlay, "overlay 不能为空");
        Objects.requireNonNull(token, "token 不能为空");
        if (token.isCancellationRequested()) return rejected(ConfigurationDiagnosticCode.CANCELLED);
        if (runtime.isClosedOrActiveForSettings()) return rejected(RuntimeSettingsDiagnosticCode.ACTIVE_RUN);
        SettingsSourceSnapshot replacement = overlay.map(values -> snapshot(kind, values)).orElse(null);
        synchronized (applicationMonitor) {
            if (runtime.isClosedOrActiveForSettings()) return rejected(RuntimeSettingsDiagnosticCode.ACTIVE_RUN);
            return kind == SettingsSourceKind.SESSION
                    ? publish(user, projectShared, projectLocal, replacement, cli, ApprovalReviewer.USER, token)
                    : publish(user, projectShared, projectLocal, session, replacement, sessionReviewer, token);
        }
    }

    private Candidate loadFixed(CancellationToken token) {
        SettingsFixedSourceLoader.SettingsSourceLoadResult loadedUser = fixedLoader.loadUser(token);
        SettingsFixedSourceLoader.SettingsSourceLoadResult loadedShared = fixedLoader.loadProjectShared(token);
        SettingsFixedSourceLoader.SettingsSourceLoadResult loadedLocal = fixedLoader.loadProjectLocal(token);
        List<ConfigurationDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.addAll(loadedUser.diagnostics());
        diagnostics.addAll(loadedShared.diagnostics());
        diagnostics.addAll(loadedLocal.diagnostics());
        return new Candidate(loadedUser.snapshot().orElse(null), loadedShared.snapshot().orElse(null),
                loadedLocal.snapshot().orElse(null), diagnostics);
    }

    private SettingsApplicationResult publish(SettingsSourceSnapshot nextUser, SettingsSourceSnapshot nextShared,
                                              SettingsSourceSnapshot nextLocal, SettingsSourceSnapshot nextSession,
                                              SettingsSourceSnapshot nextCli, ApprovalReviewer candidateReviewer,
                                              CancellationToken token) {
        if (token.isCancellationRequested()) return rejected(ConfigurationDiagnosticCode.CANCELLED);
        if (runtime.isClosedOrActiveForSettings()) return rejected(RuntimeSettingsDiagnosticCode.ACTIVE_RUN);
        candidateReviewer = Objects.requireNonNull(candidateReviewer, "candidateReviewer 不能为空");
        List<SettingsSourceSnapshot> sources = new ArrayList<>();
        sources.add(defaults());
        addIfPresent(sources, nextUser);
        addIfPresent(sources, nextShared);
        addIfPresent(sources, nextLocal);
        addIfPresent(sources, nextSession);
        addIfPresent(sources, nextCli);
        SettingsResolution resolution = resolver.resolve(sources);
        if (resolution.effectiveSettings().isEmpty()) return rejected(resolution.diagnostics());
        RuntimeSettingsApplyResult prepared = applier.prepare(resolution.effectiveSettings().orElseThrow(),
                runtime::hasActiveRun, token::isCancellationRequested);
        if (!prepared.applied()) return rejectedRuntime(prepared.diagnostics());
        RuntimeConfiguration preparedConfiguration = withReviewer(prepared.configuration(),
                reviewerFor(prepared.configuration(), candidateReviewer));
        Optional<EffectiveSettingsSnapshot> previous = store.current();
        long revision = previous.map(EffectiveSettingsSnapshot::revision).orElse(0L);
        if (revision == Long.MAX_VALUE) return rejected(ConfigurationDiagnosticCode.REVISION_EXHAUSTED);
        EffectiveSettingsSnapshot candidate = new EffectiveSettingsSnapshot(revision + 1,
                resolution.effectiveSettings().orElseThrow());
        HeadlessRuntimeSession.SettingsCommitResult committed = runtime.replaceRuntimeConfigurationAtomically(
                preparedConfiguration, store, previous.map(EffectiveSettingsSnapshot::revision), candidate, token);
        return switch (committed) {
            case COMMITTED -> {
                applier.commitPrepared(preparedConfiguration);
                user = nextUser;
                projectShared = nextShared;
                projectLocal = nextLocal;
                session = nextSession;
                cli = nextCli;
                sessionReviewer = candidateReviewer;
                yield SettingsApplicationResult.published(candidate, preparedConfiguration);
            }
            case ACTIVE_OR_CLOSED -> rejected(RuntimeSettingsDiagnosticCode.ACTIVE_RUN);
            case CANCELLED -> rejected(ConfigurationDiagnosticCode.CANCELLED);
            case CAS_CONFLICT -> rejected(ConfigurationDiagnosticCode.CAS_CONFLICT);
            case INTERNAL_FAILURE -> rejected(RuntimeSettingsDiagnosticCode.INTERNAL_FAILURE);
        };
    }

    /**
     * 对兼容 PermissionMode 收敛到用户审查，避免旧 mode 携带自动 reviewer。
     */
    private static ApprovalReviewer reviewerFor(RuntimeConfiguration configuration, ApprovalReviewer candidateReviewer) {
        return configuration.permissionMode() == io.github.liumaishenjian.ccjava.domain.PermissionMode.DEFAULT
                ? candidateReviewer : ApprovalReviewer.USER;
    }

    /**
     * 只替换已验证候选的 final-ASK reviewer，其他 Settings 投影字段保持同一原子候选。
     */
    private static RuntimeConfiguration withReviewer(RuntimeConfiguration configuration, ApprovalReviewer reviewer) {
        return new RuntimeConfiguration(configuration.modelName(), configuration.permissionMode(), reviewer,
                configuration.permissionRules(), configuration.enabledBuiltinTools(), configuration.toolConfigurations(),
                configuration.compactAnchors(), configuration.diagnosticsVerbosity());
    }

    private static void addIfPresent(List<SettingsSourceSnapshot> snapshots, SettingsSourceSnapshot candidate) {
        if (candidate != null) snapshots.add(candidate);
    }

    private static SettingsSourceSnapshot defaults() {
        return new SettingsSourceSnapshot(DEFAULTS_ID, DEFAULTS_REVISION, emptySettings(), List.of());
    }

    private static SettingsSourceSnapshot snapshot(SettingsSourceKind kind, DeclaredSettings values) {
        if (kind != SettingsSourceKind.SESSION && kind != SettingsSourceKind.CLI) {
            throw new IllegalArgumentException("overlay 仅允许 SESSION 或 CLI 来源");
        }
        return new SettingsSourceSnapshot(new SettingsSourceId(kind, kind.name().toLowerCase() + "-overlay"),
                DEFAULTS_REVISION, values, List.of());
    }

    private static DeclaredSettings emptySettings() {
        return new DeclaredSettings(Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Map.of(), List.of(), Optional.empty());
    }

    private SettingsApplicationResult rejected(ConfigurationDiagnosticCode code) {
        return rejected(List.of(new ConfigurationDiagnostic(APPLICATION_ID, code,
                ConfigurationDiagnosticSeverity.ERROR, Optional.empty())));
    }

    private SettingsApplicationResult rejected(RuntimeSettingsDiagnosticCode code) {
        return rejectedRuntime(List.of(new RuntimeSettingsDiagnostic(code)));
    }

    private SettingsApplicationResult rejected(List<ConfigurationDiagnostic> diagnostics) {
        return SettingsApplicationResult.rejected(store.current(), diagnostics.stream().map(ApplicationDiagnostic::from).toList());
    }

    private SettingsApplicationResult rejectedRuntime(List<RuntimeSettingsDiagnostic> diagnostics) {
        return SettingsApplicationResult.rejected(store.current(), diagnostics.stream().map(ApplicationDiagnostic::from).toList());
    }

    private record Candidate(SettingsSourceSnapshot user, SettingsSourceSnapshot projectShared,
                             SettingsSourceSnapshot projectLocal, List<ConfigurationDiagnostic> diagnostics) {
        private Candidate { diagnostics = List.copyOf(diagnostics); }
    }

    /** Settings Application 的封闭安全诊断投影，不含正文、绝对路径或凭证。 */
    public sealed interface ApplicationDiagnostic permits ConfigurationFailure, RuntimeFailure {
        /**
         * 将 Domain 配置诊断转换为安全应用投影。
         *
         * @param diagnostic 原始安全 Domain 诊断
         * @return 不含来源正文或路径的应用诊断
         */
        static ApplicationDiagnostic from(ConfigurationDiagnostic diagnostic) {
            return new ConfigurationFailure(diagnostic.code(), diagnostic.sourceId().kind(), diagnostic.sourceId().safeId(),
                    diagnostic.fieldPath().map(Object::toString));
        }
        /**
         * 将 Runtime 投影诊断转换为安全应用投影。
         *
         * @param diagnostic 原始 Runtime 固定分类
         * @return 不含外部值的应用诊断
         */
        static ApplicationDiagnostic from(RuntimeSettingsDiagnostic diagnostic) {
            return new RuntimeFailure(diagnostic.code());
        }
    }

    /**
     * 固定来源/字段的配置诊断。
     *
     * @param code 固定配置失败分类
     * @param sourceKind 固定来源类别
     * @param safeSourceId 非路径安全来源标识
     * @param fieldPath 可选受限字段路径
     */
    public record ConfigurationFailure(ConfigurationDiagnosticCode code, SettingsSourceKind sourceKind,
                                       String safeSourceId, Optional<String> fieldPath) implements ApplicationDiagnostic {
        /**
         * 创建只含安全来源标识和字段投影的配置失败。
         *
         * @param code 固定配置失败分类
         * @param sourceKind 固定来源类别
         * @param safeSourceId 非路径安全来源标识
         * @param fieldPath 可选受限字段路径
         */
        public ConfigurationFailure {
            code = Objects.requireNonNull(code, "code 不能为空");
            sourceKind = Objects.requireNonNull(sourceKind, "sourceKind 不能为空");
            safeSourceId = Objects.requireNonNull(safeSourceId, "safeSourceId 不能为空");
            fieldPath = Objects.requireNonNull(fieldPath, "fieldPath 不能为空");
        }
        @Override public String toString() { return "ConfigurationFailure[code=" + code + ", sourceKind=" + sourceKind + ", fieldPath=" + fieldPath + "]"; }
    }

    /**
     * 不携带外部输入的 Runtime 映射/提交诊断。
     *
     * @param code Runtime 映射或提交失败分类
     */
    public record RuntimeFailure(RuntimeSettingsDiagnosticCode code) implements ApplicationDiagnostic {
        /**
         * 创建只含固定 Runtime 分类的失败。
         *
         * @param code Runtime 映射或提交失败分类
         */
        public RuntimeFailure { code = Objects.requireNonNull(code, "code 不能为空"); }
        @Override public String toString() { return "RuntimeFailure[code=" + code + "]"; }
    }

    /**
     * 一次 Settings Application 尝试的安全结果。
     *
     * @param snapshot 当前或新发布的 LKG
     * @param runtimeConfiguration 仅成功时存在的完整 Runtime 配置
     * @param published 是否完成 LKG/Scope 配对提交
     * @param diagnostics 失败时的封闭安全诊断
     */
    public record SettingsApplicationResult(Optional<EffectiveSettingsSnapshot> snapshot,
                                            Optional<RuntimeConfiguration> runtimeConfiguration,
                                            boolean published, List<ApplicationDiagnostic> diagnostics) {
        /**
         * 冻结一次原子发布尝试的安全结果。
         *
         * @param snapshot 当前或新发布的 LKG
         * @param runtimeConfiguration 仅成功时存在的完整 Runtime 配置
         * @param published 是否完成 LKG/Scope 配对提交
         * @param diagnostics 失败时的封闭安全诊断
         */
        public SettingsApplicationResult {
            snapshot = Objects.requireNonNull(snapshot, "snapshot 不能为空");
            runtimeConfiguration = Objects.requireNonNull(runtimeConfiguration, "runtimeConfiguration 不能为空");
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics 不能为空"));
            if (published != diagnostics.isEmpty()) throw new IllegalArgumentException("发布与诊断状态不一致");
        }
        static SettingsApplicationResult published(EffectiveSettingsSnapshot snapshot, RuntimeConfiguration configuration) {
            return new SettingsApplicationResult(Optional.of(snapshot), Optional.of(configuration), true, List.of());
        }
        static SettingsApplicationResult rejected(Optional<EffectiveSettingsSnapshot> snapshot,
                                                  List<ApplicationDiagnostic> diagnostics) {
            return new SettingsApplicationResult(snapshot, Optional.empty(), false, diagnostics);
        }
        @Override public String toString() {
            return "SettingsApplicationResult[snapshot=" + snapshot.map(value -> "<lkg>" ).orElse("<empty>")
                    + ", runtimeConfiguration=" + runtimeConfiguration.map(value -> "<applied>").orElse("<empty>")
                    + ", published=" + published + ", diagnostics=" + diagnostics + "]";
        }
    }
}
