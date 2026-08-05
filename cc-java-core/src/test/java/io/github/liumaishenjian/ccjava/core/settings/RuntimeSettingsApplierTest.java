package io.github.liumaishenjian.ccjava.core.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolRegistry;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.PermissionReason;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredPermissionRuleDefinition;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredSettings;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredToolConfiguration;
import io.github.liumaishenjian.ccjava.domain.settings.EffectiveCompactInstruction;
import io.github.liumaishenjian.ccjava.domain.settings.EffectivePermissionRule;
import io.github.liumaishenjian.ccjava.domain.settings.EffectiveSettings;
import io.github.liumaishenjian.ccjava.domain.settings.EffectiveToolConfiguration;
import io.github.liumaishenjian.ccjava.domain.settings.ProvenancedSettingValue;
import io.github.liumaishenjian.ccjava.domain.settings.RuntimeConfiguration;
import io.github.liumaishenjian.ccjava.domain.settings.RuntimeDiagnosticsVerbosity;
import io.github.liumaishenjian.ccjava.domain.settings.RuntimeSettingsDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.settings.SettingOperation;
import io.github.liumaishenjian.ccjava.domain.settings.SettingProvenance;
import io.github.liumaishenjian.ccjava.domain.settings.SettingValidationStatus;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceId;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceKind;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuntimeSettingsApplierTest {
    private static final SettingProvenance PROVENANCE = new SettingProvenance(
            new SettingsSourceId(SettingsSourceKind.SESSION, "session"), 4,
            SettingOperation.SET, SettingValidationStatus.VALID);

    @Test
    void atomicallyProjectsOnlyTrustedNextRunInputs() {
        RuntimeSettingsApplier applier = applier();

        var result = applier.apply(settings("safe-model", "ACCEPT_EDITS",
                List.of(rule("ask-write", "ASK", "WRITE_WORKSPACE", "write_file", "BUILT_IN", "src/Main.java")),
                List.of("read_file"), Map.of("read_file", replacement(Map.of("maxLines", 20))),
                List.of("retain facts"), "DETAIL"), () -> false, () -> false);

        assertThat(result.applied()).isTrue();
        assertThat(result.configuration().modelName()).contains("safe-model");
        assertThat(result.configuration().permissionMode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
        assertThat(result.configuration().permissionRules()).hasSize(2);
        assertThat(result.configuration().permissionRules()).allSatisfy(rule ->
                assertThat(rule.source().name()).isEqualTo("STARTUP"));
        assertThat(result.configuration().enabledBuiltinTools()).containsExactly("read_file");
        assertThat(result.configuration().toolConfigurations().get("read_file").values()).containsEntry("maxLines", 20);
        assertThat(result.configuration().compactAnchors()).containsExactly("baseline anchor", "retain facts");
        assertThat(result.configuration().diagnosticsVerbosity()).isEqualTo(RuntimeDiagnosticsVerbosity.DETAIL);
    }

    @Test
    void emptyEffectiveSettingsAfterShrinkRestoreEveryBaselineField() {
        RuntimeSettingsApplier applier = applier();
        var shrunk = applier.apply(settings(null, "PLAN", List.of(), List.of("read_file"),
                Map.of("read_file", replacement(Map.of("maxLines", 20))), List.of("temporary anchor"), "OFF"),
                () -> false, () -> false);
        var restored = applier.apply(settings(null, null, List.of(), null, Map.of(), List.of(), null),
                () -> false, () -> false);

        assertThat(shrunk.configuration().enabledBuiltinTools()).containsExactly("read_file");
        assertThat(shrunk.configuration().permissionRules()).hasSize(1);
        assertThat(shrunk.configuration().compactAnchors()).containsExactly("baseline anchor", "temporary anchor");
        assertThat(restored.applied()).isTrue();
        assertThat(restored.configuration()).isEqualTo(applierBaseline());
        assertThat(restored.configuration().modelName()).contains("safe-model");
        assertThat(restored.configuration().permissionMode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(restored.configuration().permissionRules()).hasSize(1);
        assertThat(restored.configuration().enabledBuiltinTools()).containsExactly("read_file", "write_file");
        assertThat(restored.configuration().toolConfigurations().get("read_file").values())
                .containsEntry("maxLines", 10);
        assertThat(restored.configuration().compactAnchors()).containsExactly("baseline anchor");
        assertThat(restored.configuration().diagnosticsVerbosity()).isEqualTo(RuntimeDiagnosticsVerbosity.SUMMARY);
    }

    @Test
    void activeRunAndCancellationPreservePriorConfiguration() {
        RuntimeSettingsApplier applier = applier();
        RuntimeConfiguration before = applier.current();

        var active = applier.apply(settings("safe-model", null, List.of(), null, Map.of(), List.of(), null),
                () -> true, () -> false);
        var cancelled = applier.apply(settings("safe-model", null, List.of(), null, Map.of(), List.of(), null),
                () -> false, () -> true);

        assertThat(active.applied()).isFalse();
        assertThat(active.configuration()).isSameAs(before);
        assertThat(active.diagnostic()).isPresent();
        assertThat(active.diagnostic().orElseThrow().code())
                .isEqualTo(RuntimeSettingsDiagnosticCode.ACTIVE_RUN);
        assertThat(cancelled.applied()).isFalse();
        assertThat(cancelled.configuration()).isSameAs(before);
        assertThat(cancelled.diagnostic()).isPresent();
        assertThat(cancelled.diagnostic().orElseThrow().code())
                .isEqualTo(RuntimeSettingsDiagnosticCode.CANCELLED);
    }

    @Test
    void rejectsUnknownModelToolConfigurationAndRuleWithoutPartialReplacement() {
        RuntimeSettingsApplier applier = applier();
        RuntimeConfiguration before = applier.current();

        assertRejected(applier.apply(settings("unknown-model", null, List.of(), null, Map.of(), List.of(), null),
                () -> false, () -> false), before, RuntimeSettingsDiagnosticCode.UNSUPPORTED_MODEL);
        assertRejected(applier.apply(settings(null, null, List.of(), List.of("missing"), Map.of(), List.of(), null),
                () -> false, () -> false), before, RuntimeSettingsDiagnosticCode.INVALID_TOOL_VISIBILITY);
        assertRejected(applier.apply(settings(null, null, List.of(), null,
                Map.of("missing", replacement(Map.of("maxLines", 1))), List.of(), null), () -> false, () -> false),
                before, RuntimeSettingsDiagnosticCode.INVALID_TOOL_CONFIGURATION);
        assertRejected(applier.apply(settings(null, null,
                List.of(rule("bad", "ALLOW", "READ_WORKSPACE", "read_file", "MCP", "x")), null,
                Map.of(), List.of(), null), () -> false, () -> false), before,
                RuntimeSettingsDiagnosticCode.INVALID_PERMISSION_RULE);
    }

    @Test
    void cannotExpandToolVisibilityOrAlterSourceAndEffect() {
        RuntimeSettingsApplier applier = applier();
        RuntimeConfiguration before = applier.current();

        assertRejected(applier.apply(settings(null, null, List.of(), List.of("read_file", "run_command"),
                Map.of(), List.of(), null), () -> false, () -> false), before,
                RuntimeSettingsDiagnosticCode.INVALID_TOOL_VISIBILITY);
        assertRejected(applier.apply(settings(null, null,
                List.of(rule("bad-effect", "ALLOW", "READ_WORKSPACE", "write_file", "BUILT_IN", "src/Main.java")),
                null, Map.of(), List.of(), null), () -> false, () -> false), before,
                RuntimeSettingsDiagnosticCode.INVALID_PERMISSION_RULE);
    }

    @Test
    void acceptsOnlySchemaApprovedNonSecretConfigurations() {
        RuntimeSettingsApplier applier = applier();
        RuntimeConfiguration before = applier.current();

        assertRejected(applier.apply(settings(null, null, List.of(), null,
                Map.of("read_file", replacement(Map.of("apiKey", "secret"))), List.of(), null), () -> false, () -> false),
                before, RuntimeSettingsDiagnosticCode.FORBIDDEN_TOOL_CONFIGURATION);
        assertRejected(applier.apply(settings(null, null, List.of(), null,
                Map.of("read_file", replacement(Map.of("other", 1))), List.of(), null), () -> false, () -> false),
                before, RuntimeSettingsDiagnosticCode.INVALID_TOOL_CONFIGURATION);
    }

    @Test
    void rejectsSensitiveConfigurationKeyAliasesInNestedObjectsAndLists() {
        RuntimeSettingsApplier applier = applierWithSchemas(Map.of("read_file", configuration -> true));
        RuntimeConfiguration before = applier.current();

        assertRejected(applier.apply(settings(null, null, List.of(), null,
                Map.of("read_file", replacement(Map.of("api.key", "value"))), List.of(), null),
                () -> false, () -> false), before, RuntimeSettingsDiagnosticCode.FORBIDDEN_TOOL_CONFIGURATION);
        assertRejected(applier.apply(settings(null, null, List.of(), null,
                Map.of("read_file", replacement(Map.of("options", List.of(
                        Map.of("base url", "https://example.invalid"))))), List.of(), null),
                () -> false, () -> false), before, RuntimeSettingsDiagnosticCode.FORBIDDEN_TOOL_CONFIGURATION);
        assertRejected(applier.apply(settings(null, null, List.of(), null,
                Map.of("read_file", replacement(Map.of("authToken", "value"))), List.of(), null),
                () -> false, () -> false), before, RuntimeSettingsDiagnosticCode.FORBIDDEN_TOOL_CONFIGURATION);
        assertRejected(applier.apply(settings(null, null, List.of(), null,
                Map.of("read_file", replacement(Map.of("options", List.of(
                        Map.of("clientSecret", "value"),
                        Map.of("providerBaseUrl", "https://example.invalid"),
                        Map.of("serviceEndpoint", "https://example.invalid"))))), List.of(), null),
                () -> false, () -> false), before, RuntimeSettingsDiagnosticCode.FORBIDDEN_TOOL_CONFIGURATION);

        var accepted = applier.apply(settings(null, null, List.of(), null,
                Map.of("read_file", replacement(Map.of("maxLines", 20))), List.of(), null),
                () -> false, () -> false);
        assertThat(accepted.applied()).isTrue();
        assertThat(accepted.configuration().toolConfigurations().get("read_file").values())
                .containsEntry("maxLines", 20);
    }

    @Test
    void throwingSchemaFailsClosedWithoutPartialReplacement() {
        RuntimeSettingsApplier applier = applierWithSchemas(Map.of("read_file", configuration -> {
            throw new IllegalStateException("schema failure");
        }));
        RuntimeConfiguration before = applier.current();

        assertRejected(applier.apply(settings(null, null, List.of(), null,
                Map.of("read_file", replacement(Map.of("maxLines", 20))), List.of(), null), () -> false, () -> false),
                before, RuntimeSettingsDiagnosticCode.INVALID_TOOL_CONFIGURATION);
    }

    @Test
    void toolConfigurationIterationKeepsEffectiveSettingsOrder() {
        RuntimeSettingsApplier applier = applierWithSchemas(Map.of(
                "read_file", configuration -> true,
                "write_file", configuration -> true));
        var result = applier.apply(settings(null, null, List.of(), null,
                orderedConfigurations(), List.of(), null), () -> false, () -> false);

        assertThat(result.applied()).isTrue();
        assertThat(result.configuration().toolConfigurations().keySet())
                .containsExactly("read_file", "write_file");
    }

    @Test
    void rulesRemainSubjectToExistingS05FixedPriorityAndTrustedSource() {
        RuntimeSettingsApplier applier = applier();
        var result = applier.apply(settings(null, "PLAN", List.of(
                rule("allow", "ALLOW", "WRITE_WORKSPACE", "write_file", "BUILT_IN", "src/Main.java"),
                rule("ask", "ASK", "WRITE_WORKSPACE", "write_file", "BUILT_IN", "src/Main.java"),
                rule("deny", "DENY", "WRITE_WORKSPACE", "write_file", "BUILT_IN", "src/Main.java")),
                null, Map.of(), List.of(), null), () -> false, () -> false);

        var policy = new io.github.liumaishenjian.ccjava.core.PermissionPolicy(result.configuration().permissionMode(),
                result.configuration().permissionRules(),
                (invocation, definition) -> new io.github.liumaishenjian.ccjava.domain.PermissionSelector(
                        definition.name(), definition.source(), "src/Main.java"),
                (invocation, definition, selector) -> false,
                new io.github.liumaishenjian.ccjava.core.InMemorySessionPermissionState());
        var outcome = policy.evaluate(new io.github.liumaishenjian.ccjava.core.ToolInvocation(
                        new io.github.liumaishenjian.ccjava.domain.SessionId("session"),
                        new io.github.liumaishenjian.ccjava.domain.RunId("run"), 1,
                        new io.github.liumaishenjian.ccjava.domain.ToolCall("call", "write_file", JsonObject.empty())),
                definition("write_file", ToolEffect.WRITE_WORKSPACE, ToolSource.BUILT_IN));

        assertThat(outcome.decision()).isEqualTo(PermissionDecision.DENY);
        assertThat(outcome.reason()).isEqualTo(PermissionReason.EXPLICIT_DENY);

        var hardDenied = new io.github.liumaishenjian.ccjava.core.PermissionPolicy(
                result.configuration().permissionMode(), result.configuration().permissionRules(),
                (invocation, definition) -> new io.github.liumaishenjian.ccjava.domain.PermissionSelector(
                        definition.name(), definition.source(), "src/Main.java"),
                (invocation, definition, selector) -> true,
                new io.github.liumaishenjian.ccjava.core.InMemorySessionPermissionState())
                .evaluate(new io.github.liumaishenjian.ccjava.core.ToolInvocation(
                        new io.github.liumaishenjian.ccjava.domain.SessionId("session"),
                        new io.github.liumaishenjian.ccjava.domain.RunId("run"), 1,
                        new io.github.liumaishenjian.ccjava.domain.ToolCall("call", "write_file", JsonObject.empty())),
                        definition("write_file", ToolEffect.WRITE_WORKSPACE, ToolSource.BUILT_IN));
        assertThat(hardDenied.decision()).isEqualTo(PermissionDecision.DENY);
        assertThat(hardDenied.reason()).isEqualTo(PermissionReason.HARD_DENIAL);
    }

    @Test
    void rejectsSessionRulesInRuntimeBaseline() {
        RuntimeConfiguration trusted = applierBaseline();
        RuntimeConfiguration sessionBaseline = new RuntimeConfiguration(trusted.modelName(), trusted.permissionMode(),
                List.of(new io.github.liumaishenjian.ccjava.domain.PermissionRule(
                        io.github.liumaishenjian.ccjava.domain.PermissionRuleSource.SESSION,
                        PermissionDecision.ALLOW,
                        new io.github.liumaishenjian.ccjava.domain.PermissionSelector(
                                "write_file", ToolSource.BUILT_IN, "src/Main.java"))),
                trusted.enabledBuiltinTools(), trusted.toolConfigurations(), trusted.compactAnchors(),
                trusted.diagnosticsVerbosity());

        assertThatThrownBy(() -> applierWithInitial(sessionBaseline, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STARTUP");
    }

    @Test
    void redactsCandidateValuesFromAllRuntimeValueObjects() {
        String secret = "private-token-should-never-appear";
        RuntimeSettingsApplier applier = applier();
        var result = applier.apply(settings("safe-model", null, List.of(), null,
                Map.of("read_file", replacement(Map.of("maxLines", 1))), List.of(secret), "SUMMARY"),
                () -> false, () -> false);

        assertThat(result.toString()).doesNotContain(secret, "safe-model");
        assertThat(result.configuration().toString()).doesNotContain(secret, "safe-model");
    }

    private static void assertRejected(io.github.liumaishenjian.ccjava.domain.settings.RuntimeSettingsApplyResult result,
                                       RuntimeConfiguration before, RuntimeSettingsDiagnosticCode code) {
        assertThat(result.applied()).isFalse();
        assertThat(result.configuration()).isSameAs(before);
        assertThat(result.diagnostic()).isPresent();
        assertThat(result.diagnostic().orElseThrow().code()).isEqualTo(code);
    }

    private static RuntimeConfiguration applierBaseline() {
        return new RuntimeConfiguration(Optional.of("safe-model"), PermissionMode.DEFAULT,
                List.of(new io.github.liumaishenjian.ccjava.domain.PermissionRule(
                        io.github.liumaishenjian.ccjava.domain.PermissionRuleSource.STARTUP,
                        PermissionDecision.ASK,
                        new io.github.liumaishenjian.ccjava.domain.PermissionSelector(
                                "write_file", ToolSource.BUILT_IN, "baseline.txt"))),
                List.of("read_file", "write_file"),
                Map.of("read_file", new JsonObject(Map.of("maxLines", 10))), List.of("baseline anchor"),
                RuntimeDiagnosticsVerbosity.SUMMARY);
    }

    private static Map<String, EffectiveToolConfiguration> orderedConfigurations() {
        java.util.LinkedHashMap<String, EffectiveToolConfiguration> configurations = new java.util.LinkedHashMap<>();
        configurations.put("read_file", replacement(Map.of("maxLines", 20)));
        configurations.put("write_file", replacement(Map.of("lineEnding", "LF")));
        return configurations;
    }

    private static RuntimeSettingsApplier applier() {
        return applierWithSchemas(Map.of("read_file", configuration ->
                configuration.values().keySet().equals(java.util.Set.of("maxLines"))));
    }

    private static RuntimeSettingsApplier applierWithSchemas(Map<String, TrustedToolConfigurationSchema> schemas) {
        return applierWithInitial(applierBaseline(), schemas);
    }

    private static RuntimeSettingsApplier applierWithInitial(
            RuntimeConfiguration initial, Map<String, TrustedToolConfigurationSchema> schemas) {
        ToolRegistry registry = new ToolRegistry(List.of(
                tool(definition("read_file", ToolEffect.READ_WORKSPACE, ToolSource.BUILT_IN)),
                tool(definition("write_file", ToolEffect.WRITE_WORKSPACE, ToolSource.BUILT_IN)),
                tool(definition("run_command", ToolEffect.EXECUTE_PROCESS, ToolSource.BUILT_IN)),
                tool(definition("plugin_tool", ToolEffect.READ_WORKSPACE, ToolSource.PLUGIN))));
        return new RuntimeSettingsApplier(initial, List.of("safe-model"), registry, schemas);
    }

    private static EffectiveSettings settings(String model, String mode, List<DeclaredPermissionRuleDefinition> rules,
                                              List<String> enabled, Map<String, EffectiveToolConfiguration> configurations,
                                              List<String> anchors, String verbosity) {
        return new EffectiveSettings(Optional.ofNullable(model).map(value -> new ProvenancedSettingValue<>(value, PROVENANCE)),
                Optional.ofNullable(mode).map(value -> new ProvenancedSettingValue<>(value, PROVENANCE)),
                rules.stream().map(rule -> new EffectivePermissionRule(rule, PROVENANCE)).toList(),
                Optional.ofNullable(enabled).map(values -> new ProvenancedSettingValue<>(
                        values.stream().map(value -> new ProvenancedSettingValue<>(value, PROVENANCE)).toList(), PROVENANCE)),
                configurations,
                anchors.stream().map(value -> new EffectiveCompactInstruction(value, PROVENANCE, List.of())).toList(),
                Map.of(), Optional.ofNullable(verbosity).map(value -> new ProvenancedSettingValue<>(value, PROVENANCE)), List.of());
    }

    private static EffectiveToolConfiguration replacement(Map<String, ?> values) {
        return new EffectiveToolConfiguration(new DeclaredToolConfiguration.Replacement(new JsonObject(values)), PROVENANCE);
    }

    private static DeclaredPermissionRuleDefinition rule(String id, String decision, String effect, String tool,
                                                          String source, String selector) {
        return new DeclaredPermissionRuleDefinition(id, decision, effect, tool, source, selector);
    }

    private static AgentTool tool(ToolDefinition definition) {
        return new AgentTool() {
            @Override public ToolDefinition definition() { return definition; }
            @Override public ToolExecutionOutcome execute(io.github.liumaishenjian.ccjava.core.ToolInvocation invocation) {
                return ToolExecutionOutcome.success("unused");
            }
        };
    }

    private static ToolDefinition definition(String name, ToolEffect effect, ToolSource source) {
        return new ToolDefinition(name, "test", "{}", effect, source, false,
                Duration.ofSeconds(1), "text/plain", 100);
    }
}
