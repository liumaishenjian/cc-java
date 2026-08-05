package io.github.liumaishenjian.ccjava.core.settings;

import io.github.liumaishenjian.ccjava.core.ToolRegistry;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.PermissionRule;
import io.github.liumaishenjian.ccjava.domain.PermissionRuleSource;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredPermissionRuleDefinition;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredToolConfiguration;
import io.github.liumaishenjian.ccjava.domain.settings.EffectiveSettings;
import io.github.liumaishenjian.ccjava.domain.settings.RuntimeConfiguration;
import io.github.liumaishenjian.ccjava.domain.settings.RuntimeDiagnosticsVerbosity;
import io.github.liumaishenjian.ccjava.domain.settings.RuntimeSettingsApplyResult;
import io.github.liumaishenjian.ccjava.domain.settings.RuntimeSettingsDiagnosticCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 将已解析 Settings 安全投影为下一次 Run 的完整 RuntimeConfiguration。
 *
 * <p>本服务不读取文件、不解析 JSON、不装配 Provider、不执行 Tool，也不修改 S05 Policy、
 * Session Grant 或审批生命周期。每次应用都以启动基线重建完整候选：可信启动规则和保护锚点
 * 始终保留，Settings 规则随后追加，锚点按精确文本去重追加；被移除的 Settings 覆盖会恢复
 * 基线。任一映射或取消失败都会保留先前配置，避免观察到部分状态。</p>
 *
 * @since 0.8.0
 */
public final class RuntimeSettingsApplier {
    private static final Set<String> FORBIDDEN_CONFIGURATION_KEYS = Set.of(
            "url", "effect", "source", "toolsource", "workspace", "shell", "network", "sandbox",
            "deadline", "sensitivepath", "resultcap", "maxoutput");
    private static final Set<String> FORBIDDEN_CONFIGURATION_KEY_PARTS = Set.of(
            "apikey", "token", "secret", "password", "credential", "endpoint", "baseurl", "timeout");

    private final Set<String> supportedModels;
    private final Map<String, ToolDefinition> builtinDefinitions;
    private final Map<String, TrustedToolConfigurationSchema> configurationSchemas;
    private final RuntimeConfiguration baseline;
    private RuntimeConfiguration current;

    /**
     * 创建一个仅接受调用方显式信任边界的应用器。
     *
     * @param initial 初始完整 Runtime 配置
     * @param providerSupportedModels Provider 已装配并支持的模型名称
     * @param registry 当前 Tool Registry
     * @param trustedConfigurationSchemas builtin Tool 的可信非敏感配置 schema
     */
    public RuntimeSettingsApplier(
            RuntimeConfiguration initial,
            Collection<String> providerSupportedModels,
            ToolRegistry registry,
            Map<String, TrustedToolConfigurationSchema> trustedConfigurationSchemas) {
        baseline = Objects.requireNonNull(initial, "initial 不能为空");
        current = baseline;
        supportedModels = freezeModels(providerSupportedModels);
        builtinDefinitions = builtinDefinitions(registry);
        configurationSchemas = freezeSchemas(trustedConfigurationSchemas);
        if (!builtinDefinitions.keySet().containsAll(current.enabledBuiltinTools())) {
            throw new IllegalArgumentException("initial 含未注册 builtin Tool");
        }
        if (!builtinDefinitions.keySet().containsAll(current.toolConfigurations().keySet())) {
            throw new IllegalArgumentException("initial 含未注册 builtin Tool 配置");
        }
        if (current.permissionRules().stream().anyMatch(rule -> rule.source() != PermissionRuleSource.STARTUP)) {
            throw new IllegalArgumentException("initial 只能包含 STARTUP PermissionRule");
        }
    }

    /**
     * 返回最后完整发布的下一 Run 配置。
     *
     * @return 不可变当前配置
     */
    public synchronized RuntimeConfiguration current() {
        return current;
    }

    /**
     * 在无活动 Run 时原子应用一份已解析的有效 Settings。
     *
     * @param settings 已完成来源合并的 Settings
     * @param hasActiveRun 当前会话是否存在活动 Run
     * @param cancelled 调用是否已取消
     * @return 成功时替换完整配置；失败时保留原配置与固定安全诊断
     */
    public synchronized RuntimeSettingsApplyResult apply(
            EffectiveSettings settings, BooleanSupplier hasActiveRun, BooleanSupplier cancelled) {
        RuntimeSettingsApplyResult prepared = prepare(settings, hasActiveRun, cancelled);
        if (prepared.applied()) {
            current = prepared.configuration();
        }
        return prepared;
    }

    /**
     * 在不改变当前配置的前提下构造完整候选。
     *
     * <p>Application 层可在此后先构建外部 RuntimeScope，再把候选与其余发布槽作为一个
     * 事务提交，避免 LKG 与 Scope 分别暴露。</p>
     *
     * @param settings 已完成来源合并的 Settings
     * @param hasActiveRun 当前会话是否存在活动 Run
     * @param cancelled 调用是否已取消
     * @return 候选或保留当前配置的拒绝结果
     */
    public synchronized RuntimeSettingsApplyResult prepare(
            EffectiveSettings settings, BooleanSupplier hasActiveRun, BooleanSupplier cancelled) {
        Objects.requireNonNull(settings, "settings 不能为空");
        hasActiveRun = Objects.requireNonNull(hasActiveRun, "hasActiveRun 不能为空");
        cancelled = Objects.requireNonNull(cancelled, "cancelled 不能为空");
        if (hasActiveRun.getAsBoolean()) {
            return RuntimeSettingsApplyResult.rejected(current, RuntimeSettingsDiagnosticCode.ACTIVE_RUN);
        }
        if (cancelled.getAsBoolean()) {
            return RuntimeSettingsApplyResult.rejected(current, RuntimeSettingsDiagnosticCode.CANCELLED);
        }
        try {
            RuntimeConfiguration candidate = project(settings, baseline);
            if (cancelled.getAsBoolean()) {
                return RuntimeSettingsApplyResult.rejected(current, RuntimeSettingsDiagnosticCode.CANCELLED);
            }
            if (hasActiveRun.getAsBoolean()) {
                return RuntimeSettingsApplyResult.rejected(current, RuntimeSettingsDiagnosticCode.ACTIVE_RUN);
            }
            return RuntimeSettingsApplyResult.applied(candidate);
        } catch (ProjectionFailure failure) {
            return RuntimeSettingsApplyResult.rejected(current, failure.code);
        }
    }

    /**
     * 提交已经由 {@link #prepare(EffectiveSettings, BooleanSupplier, BooleanSupplier)} 验证的候选。
     *
     * <p>调用方必须在同一会话 idle 事务中同时替换对应的 RuntimeScope；本方法不读取文件或
     * 执行 Tool。</p>
     *
     * @param configuration 已准备的完整配置
     */
    public synchronized void commitPrepared(RuntimeConfiguration configuration) {
        current = Objects.requireNonNull(configuration, "configuration 不能为空");
    }

    private RuntimeConfiguration project(EffectiveSettings settings, RuntimeConfiguration previous) {
        var model = settings.modelName().map(value -> supportedModel(value.value())).or(() -> previous.modelName());
        PermissionMode mode = settings.permissionMode().map(value -> permissionMode(value.value()))
                .orElse(previous.permissionMode());
        List<PermissionRule> rules = rules(settings, previous);
        List<String> enabled = settings.enabledTools().map(value -> enabledTools(value.value(), previous))
                .orElse(previous.enabledBuiltinTools());
        Map<String, JsonObject> configurations = configurations(settings, previous);
        List<String> anchors = anchors(settings, previous);
        RuntimeDiagnosticsVerbosity verbosity = settings.diagnosticsVerbosity()
                .map(value -> verbosity(value.value())).orElse(previous.diagnosticsVerbosity());
        return new RuntimeConfiguration(model, mode, rules, enabled, configurations, anchors, verbosity);
    }

    private String supportedModel(String model) {
        if (!supportedModels.contains(model)) {
            throw failure(RuntimeSettingsDiagnosticCode.UNSUPPORTED_MODEL);
        }
        return model;
    }

    private PermissionMode permissionMode(String value) {
        try {
            return PermissionMode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw failure(RuntimeSettingsDiagnosticCode.INVALID_PERMISSION_MODE);
        }
    }

    private List<PermissionRule> rules(EffectiveSettings settings, RuntimeConfiguration baseline) {
        ArrayList<PermissionRule> rules = new ArrayList<>(baseline.permissionRules());
        for (var effective : settings.permissionRules()) {
            DeclaredPermissionRuleDefinition rule = effective.definition();
            ToolDefinition definition = builtinDefinitions.get(rule.tool());
            if (definition == null || !definition.effect().name().equals(rule.effect())
                    || !ToolSource.BUILT_IN.name().equals(rule.toolSource())) {
                throw failure(RuntimeSettingsDiagnosticCode.INVALID_PERMISSION_RULE);
            }
            try {
                PermissionDecision behavior = PermissionDecision.valueOf(rule.decision());
                PermissionSelector selector = new PermissionSelector(rule.tool(), definition.source(), rule.selector());
                rules.add(new PermissionRule(PermissionRuleSource.STARTUP, behavior, selector));
            } catch (IllegalArgumentException exception) {
                throw failure(RuntimeSettingsDiagnosticCode.INVALID_PERMISSION_RULE);
            }
        }
        return List.copyOf(rules);
    }

    private List<String> enabledTools(List<io.github.liumaishenjian.ccjava.domain.settings.ProvenancedSettingValue<String>> requested,
                                      RuntimeConfiguration previous) {
        LinkedHashSet<String> requestedNames = new LinkedHashSet<>();
        for (var item : requested) {
            if (!builtinDefinitions.containsKey(item.value()) || !previous.enabledBuiltinTools().contains(item.value())) {
                throw failure(RuntimeSettingsDiagnosticCode.INVALID_TOOL_VISIBILITY);
            }
            requestedNames.add(item.value());
        }
        return previous.enabledBuiltinTools().stream().filter(requestedNames::contains).toList();
    }

    private Map<String, JsonObject> configurations(EffectiveSettings settings, RuntimeConfiguration previous) {
        LinkedHashMap<String, JsonObject> result = new LinkedHashMap<>(previous.toolConfigurations());
        settings.toolConfigurations().forEach((tool, effective) -> {
            if (!builtinDefinitions.containsKey(tool)) {
                throw failure(RuntimeSettingsDiagnosticCode.INVALID_TOOL_CONFIGURATION);
            }
            if (effective.declaration() instanceof DeclaredToolConfiguration.Removal) {
                result.remove(tool);
                return;
            }
            JsonObject values = ((DeclaredToolConfiguration.Replacement) effective.declaration()).values();
            if (containsForbiddenKey(values.values())) {
                throw failure(RuntimeSettingsDiagnosticCode.FORBIDDEN_TOOL_CONFIGURATION);
            }
            TrustedToolConfigurationSchema schema = configurationSchemas.get(tool);
            if (schema == null) {
                throw failure(RuntimeSettingsDiagnosticCode.INVALID_TOOL_CONFIGURATION);
            }
            try {
                if (!schema.accepts(values)) {
                    throw failure(RuntimeSettingsDiagnosticCode.INVALID_TOOL_CONFIGURATION);
                }
            } catch (ProjectionFailure failure) {
                throw failure;
            } catch (RuntimeException exception) {
                throw failure(RuntimeSettingsDiagnosticCode.INVALID_TOOL_CONFIGURATION);
            }
            result.put(tool, values);
        });
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private List<String> anchors(EffectiveSettings settings, RuntimeConfiguration baseline) {
        try {
            LinkedHashSet<String> anchors = new LinkedHashSet<>(baseline.compactAnchors());
            settings.compactInstructions().forEach(item -> anchors.add(item.instruction()));
            return List.copyOf(anchors);
        } catch (RuntimeException exception) {
            throw failure(RuntimeSettingsDiagnosticCode.INVALID_CONTEXT_OR_DIAGNOSTICS);
        }
    }

    private RuntimeDiagnosticsVerbosity verbosity(String value) {
        try {
            return RuntimeDiagnosticsVerbosity.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw failure(RuntimeSettingsDiagnosticCode.INVALID_CONTEXT_OR_DIAGNOSTICS);
        }
    }

    private static boolean containsForbiddenKey(Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (isForbiddenConfigurationKey(entry.getKey()) || containsForbiddenValue(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsForbiddenValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                if (!(entry.getKey() instanceof String key)
                        || isForbiddenConfigurationKey(key)
                        || containsForbiddenValue(entry.getValue())) {
                    return true;
                }
            }
        } else if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                if (containsForbiddenValue(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isForbiddenConfigurationKey(String key) {
        String normalized = normalizeKey(key);
        return FORBIDDEN_CONFIGURATION_KEYS.contains(normalized)
                || FORBIDDEN_CONFIGURATION_KEY_PARTS.stream().anyMatch(normalized::contains);
    }

    private static String normalizeKey(String key) {
        StringBuilder normalized = new StringBuilder(key.length());
        key.codePoints().filter(codePoint -> codePoint <= 0x7f && Character.isLetterOrDigit(codePoint))
                .forEach(codePoint -> normalized.appendCodePoint(Character.toLowerCase(codePoint)));
        return normalized.toString();
    }

    private static Set<String> freezeModels(Collection<String> models) {
        models = Objects.requireNonNull(models, "providerSupportedModels 不能为空");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String model : models) {
            if (model == null || model.isBlank() || model.codePointCount(0, model.length()) > 256
                    || model.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("providerSupportedModels 包含非法模型名");
            }
            result.add(model);
        }
        return Set.copyOf(result);
    }

    private static Map<String, ToolDefinition> builtinDefinitions(ToolRegistry registry) {
        Objects.requireNonNull(registry, "registry 不能为空");
        LinkedHashMap<String, ToolDefinition> result = new LinkedHashMap<>();
        for (ToolDefinition definition : registry.definitions()) {
            if (definition.source() == ToolSource.BUILT_IN) {
                result.put(definition.name(), definition);
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, TrustedToolConfigurationSchema> freezeSchemas(
            Map<String, TrustedToolConfigurationSchema> schemas) {
        schemas = Objects.requireNonNull(schemas, "trustedConfigurationSchemas 不能为空");
        LinkedHashMap<String, TrustedToolConfigurationSchema> result = new LinkedHashMap<>();
        schemas.forEach((tool, schema) -> result.put(Objects.requireNonNull(tool, "schema tool 不能为空"),
                Objects.requireNonNull(schema, "schema 不能为空")));
        return Map.copyOf(result);
    }

    private static ProjectionFailure failure(RuntimeSettingsDiagnosticCode code) {
        return new ProjectionFailure(code);
    }

    private static final class ProjectionFailure extends RuntimeException {
        private final RuntimeSettingsDiagnosticCode code;

        private ProjectionFailure(RuntimeSettingsDiagnosticCode code) {
            this.code = code;
        }
    }
}
