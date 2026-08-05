package io.github.liumaishenjian.ccjava.core.settings;

import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnostic;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticSeverity;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredPermissionRule;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredPermissionRuleDefinition;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredPermissionRuleRemoval;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredSettings;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredToolConfiguration;
import io.github.liumaishenjian.ccjava.domain.settings.DuplicateSuppressionProvenance;
import io.github.liumaishenjian.ccjava.domain.settings.EffectiveCompactInstruction;
import io.github.liumaishenjian.ccjava.domain.settings.EffectivePermissionRule;
import io.github.liumaishenjian.ccjava.domain.settings.EffectiveSettings;
import io.github.liumaishenjian.ccjava.domain.settings.EffectiveToolConfiguration;
import io.github.liumaishenjian.ccjava.domain.settings.ProvenancedSettingValue;
import io.github.liumaishenjian.ccjava.domain.settings.SettingOperation;
import io.github.liumaishenjian.ccjava.domain.settings.SettingPath;
import io.github.liumaishenjian.ccjava.domain.settings.SettingProvenance;
import io.github.liumaishenjian.ccjava.domain.settings.SettingValidationStatus;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceId;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceKind;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceSnapshot;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 按 S08 固定来源优先级合并已完整校验的 Settings 声明。
 *
 * <p>本服务是纯 Core 协调器：不读取文件、不解析 JSON、不发布 last-known-good 快照且不映射 S05
 * Policy。任何来源顺序、重复来源、来源字段许可或规则删除失败都会返回无最终值的原子失败结果。</p>
 *
 * @since 0.8.0
 */
public final class SettingsResolver {
    private static final List<SettingsSourceKind> ORDER = List.of(
            SettingsSourceKind.DEFAULTS,
            SettingsSourceKind.USER,
            SettingsSourceKind.PROJECT_SHARED,
            SettingsSourceKind.PROJECT_LOCAL,
            SettingsSourceKind.SESSION,
            SettingsSourceKind.CLI);

    /**
     * 创建无外部状态、可在多个请求间复用的合并器。
     */
    public SettingsResolver() {
    }

    /**
     * 合并低到高排序的有效来源快照。
     *
     * @param snapshots 每个来源最多一次的完整校验快照
     * @return 成功时的完整 {@link EffectiveSettings}，或没有部分结果的类型化失败
     */
    public SettingsResolution resolve(List<SettingsSourceSnapshot> snapshots) {
        snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots 不能为空"));
        List<ConfigurationDiagnostic> diagnostics = validateInput(snapshots);
        if (!diagnostics.isEmpty()) return SettingsResolution.failure(diagnostics);

        State state = new State();
        for (SettingsSourceSnapshot snapshot : snapshots) {
            int precedence = ORDER.indexOf(snapshot.sourceId().kind());
            diagnostics = validateSourceAllowance(snapshot, precedence);
            if (!diagnostics.isEmpty()) return SettingsResolution.failure(diagnostics);
            try {
                state.apply(snapshot, precedence);
            } catch (MissingRuleRemovalException exception) {
                return SettingsResolution.failure(List.of(diagnostic(snapshot.sourceId(),
                        ConfigurationDiagnosticCode.MISSING_RULE_REMOVAL, SettingPath.PERMISSION_RULES)));
            }
        }
        return SettingsResolution.success(state.toEffectiveSettings());
    }

    private static List<ConfigurationDiagnostic> validateInput(List<SettingsSourceSnapshot> snapshots) {
        EnumSet<SettingsSourceKind> seen = EnumSet.noneOf(SettingsSourceKind.class);
        for (int index = 0; index < snapshots.size(); index++) {
            SettingsSourceSnapshot snapshot = Objects.requireNonNull(snapshots.get(index), "snapshot 不能为空");
            SettingsSourceKind kind = snapshot.sourceId().kind();
            if (!seen.add(kind)) {
                return List.of(diagnostic(snapshot.sourceId(), ConfigurationDiagnosticCode.DUPLICATE_SOURCE, null));
            }
            if (index > 0 && ORDER.indexOf(kind) <= ORDER.indexOf(snapshots.get(index - 1).sourceId().kind())) {
                return List.of(diagnostic(snapshot.sourceId(), ConfigurationDiagnosticCode.INVALID_SOURCE_ORDER, null));
            }
        }
        return List.of();
    }

    private static List<ConfigurationDiagnostic> validateSourceAllowance(SettingsSourceSnapshot snapshot, int precedence) {
        DeclaredSettings values = snapshot.declaredValues();
        SettingsSourceKind kind = snapshot.sourceId().kind();
        if (kind == SettingsSourceKind.CLI && (!values.permissionRules().isEmpty() || !values.toolConfigurations().isEmpty())) {
            return List.of(diagnostic(snapshot.sourceId(), ConfigurationDiagnosticCode.FORBIDDEN_SOURCE_FIELD,
                    !values.permissionRules().isEmpty() ? SettingPath.PERMISSION_RULES : SettingPath.TOOLS_CONFIG));
        }
        if (kind == SettingsSourceKind.DEFAULTS && precedence != 0) {
            return List.of(diagnostic(snapshot.sourceId(), ConfigurationDiagnosticCode.INVALID_SOURCE_ORDER, null));
        }
        return List.of();
    }

    private static ConfigurationDiagnostic diagnostic(SettingsSourceId sourceId, ConfigurationDiagnosticCode code,
                                                       SettingPath path) {
        return new ConfigurationDiagnostic(sourceId, code, ConfigurationDiagnosticSeverity.ERROR, Optional.ofNullable(path));
    }

    private static final class State {
        private ProvenancedSettingValue<String> modelName;
        private ProvenancedSettingValue<String> permissionMode;
        private ProvenancedSettingValue<List<ProvenancedSettingValue<String>>> enabledTools;
        private ProvenancedSettingValue<String> diagnosticsVerbosity;
        private final LinkedHashMap<String, EffectiveToolConfiguration> toolConfigurations = new LinkedHashMap<>();
        private final LinkedHashMap<String, AnchorState> anchors = new LinkedHashMap<>();
        private final LinkedHashMap<String, EffectivePermissionRule> rules = new LinkedHashMap<>();
        private final LinkedHashMap<String, SettingProvenance> removedRules = new LinkedHashMap<>();

        void apply(SettingsSourceSnapshot snapshot, int precedence) {
            DeclaredSettings values = snapshot.declaredValues();
            SettingProvenance set = provenance(snapshot.sourceId(), precedence, SettingOperation.SET);
            values.modelName().ifPresent(value -> modelName = new ProvenancedSettingValue<>(value, set));
            values.permissionMode().ifPresent(value -> permissionMode = new ProvenancedSettingValue<>(value, set));
            values.diagnosticsVerbosity().ifPresent(value -> diagnosticsVerbosity = new ProvenancedSettingValue<>(value, set));
            values.enabledTools().ifPresent(tools -> {
                List<ProvenancedSettingValue<String>> items = tools.stream()
                        .map(tool -> new ProvenancedSettingValue<>(tool, provenance(snapshot.sourceId(), precedence,
                                SettingOperation.REPLACE)))
                        .toList();
                enabledTools = new ProvenancedSettingValue<>(items, provenance(snapshot.sourceId(), precedence,
                        SettingOperation.REPLACE));
            });
            applyTools(snapshot.sourceId(), precedence, values.toolConfigurations());
            applyAnchors(snapshot.sourceId(), precedence, values.compactInstructions());
            applyRules(snapshot.sourceId(), precedence, values.permissionRules());
        }

        private void applyTools(SettingsSourceId sourceId, int precedence,
                                Map<String, DeclaredToolConfiguration> configurations) {
            configurations.forEach((tool, declaration) -> {
                SettingOperation operation = declaration instanceof DeclaredToolConfiguration.Removal
                        ? SettingOperation.REMOVE : SettingOperation.REPLACE;
                EffectiveToolConfiguration effective = new EffectiveToolConfiguration(declaration,
                        provenance(sourceId, precedence, operation));
                toolConfigurations.put(tool, effective);
            });
        }

        private void applyAnchors(SettingsSourceId sourceId, int precedence, List<String> compactInstructions) {
            for (String instruction : compactInstructions) {
                AnchorState existing = anchors.get(instruction);
                SettingProvenance provenance = provenance(sourceId, precedence, SettingOperation.APPEND);
                if (existing == null) anchors.put(instruction, new AnchorState(instruction, provenance));
                else existing.suppressed.add(new DuplicateSuppressionProvenance(provenance));
            }
        }

        private void applyRules(SettingsSourceId sourceId, int precedence, List<DeclaredPermissionRule> declarations) {
            var lowerRuleIds = Set.copyOf(rules.keySet());
            for (DeclaredPermissionRule declaration : declarations) {
                if (declaration instanceof DeclaredPermissionRuleRemoval removal) {
                    if (!lowerRuleIds.contains(removal.ruleId()) || rules.remove(removal.ruleId()) == null) {
                        throw new MissingRuleRemovalException();
                    }
                    removedRules.put(removal.ruleId(), provenance(sourceId, precedence, SettingOperation.REMOVE));
                } else {
                    DeclaredPermissionRuleDefinition definition = (DeclaredPermissionRuleDefinition) declaration;
                    rules.put(definition.ruleId(), new EffectivePermissionRule(definition,
                            provenance(sourceId, precedence, rules.containsKey(definition.ruleId())
                                    ? SettingOperation.REPLACE : SettingOperation.SET)));
                    removedRules.remove(definition.ruleId());
                }
            }
        }

        private EffectiveSettings toEffectiveSettings() {
            List<EffectiveCompactInstruction> compactInstructions = anchors.values().stream()
                    .map(anchor -> new EffectiveCompactInstruction(anchor.instruction, anchor.provenance, anchor.suppressed))
                    .toList();
            return new EffectiveSettings(Optional.ofNullable(modelName), Optional.ofNullable(permissionMode),
                    List.copyOf(rules.values()), Optional.ofNullable(enabledTools), toolConfigurations, compactInstructions,
                    removedRules, Optional.ofNullable(diagnosticsVerbosity), List.of());
        }

        private static SettingProvenance provenance(SettingsSourceId sourceId, int precedence, SettingOperation operation) {
            return new SettingProvenance(sourceId, precedence, operation, SettingValidationStatus.VALID);
        }
    }

    private static final class AnchorState {
        private final String instruction;
        private final SettingProvenance provenance;
        private final List<DuplicateSuppressionProvenance> suppressed = new ArrayList<>();

        private AnchorState(String instruction, SettingProvenance provenance) {
            this.instruction = instruction;
            this.provenance = provenance;
        }
    }

    private static final class MissingRuleRemovalException extends RuntimeException { }
}
