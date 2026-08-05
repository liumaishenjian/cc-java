package io.github.liumaishenjian.ccjava.core.settings;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredPermissionRule;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredPermissionRuleDefinition;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredPermissionRuleRemoval;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredSettings;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredToolConfiguration;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredToolConfiguration.Removal;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredToolConfiguration.Replacement;
import io.github.liumaishenjian.ccjava.domain.settings.SettingOperation;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsRevision;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceId;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceKind;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SettingsResolverTest {
    private final SettingsResolver resolver = new SettingsResolver();

    @Test
    void mergesSixLayersUsingDeclaredLowToHighOrderAndTracksFinalProvenance() {
        SettingsResolution result = resolver.resolve(List.of(
                snapshot(SettingsSourceKind.DEFAULTS, settings("default", "DEFAULT", List.of(), List.of("read_file"), Map.of(), List.of("base"), "OFF")),
                snapshot(SettingsSourceKind.USER, settings("user", null, List.of(), null, Map.of(), List.of("user"), null)),
                snapshot(SettingsSourceKind.PROJECT_SHARED, settings(null, "PLAN", List.of(), List.of("search_text"), Map.of(), List.of("base", "shared"), null)),
                snapshot(SettingsSourceKind.PROJECT_LOCAL, settings(null, null, List.of(), null, Map.of(), List.of("local"), "SUMMARY")),
                snapshot(SettingsSourceKind.SESSION, settings("session", null, List.of(), null, Map.of(), List.of("shared", "session"), null)),
                snapshot(SettingsSourceKind.CLI, settings("cli", "ACCEPT_EDITS", List.of(), List.of("git_diff"), Map.of(), List.of("cli"), "DETAIL"))));

        assertThat(result.effectiveSettings()).isPresent();
        var effective = result.effectiveSettings().orElseThrow();
        assertThat(effective.modelName().orElseThrow().value()).isEqualTo("cli");
        assertThat(effective.modelName().orElseThrow().provenance().sourceId().kind()).isEqualTo(SettingsSourceKind.CLI);
        assertThat(effective.permissionMode().orElseThrow().value()).isEqualTo("ACCEPT_EDITS");
        assertThat(effective.enabledTools().orElseThrow().value()).extracting(value -> value.value()).containsExactly("git_diff");
        assertThat(effective.enabledTools().orElseThrow().provenance().sourceId().kind()).isEqualTo(SettingsSourceKind.CLI);
        assertThat(effective.enabledTools().orElseThrow().value().getFirst().provenance().operation())
                .isEqualTo(SettingOperation.REPLACE);
        assertThat(effective.compactInstructions()).extracting(item -> item.instruction())
                .containsExactly("base", "user", "shared", "local", "session", "cli");
        assertThat(effective.compactInstructions().getFirst().suppressedDuplicates()).hasSize(1);
        assertThat(effective.compactInstructions().getFirst().suppressedDuplicates().getFirst().provenance().sourceId().kind())
                .isEqualTo(SettingsSourceKind.PROJECT_SHARED);
    }

    @Test
    void nullAndEmptyValuesRespectNoOpAndWholeListReplacement() {
        SettingsResolution result = resolver.resolve(List.of(
                snapshot(SettingsSourceKind.DEFAULTS, settings("default", "DEFAULT", List.of(), List.of("read_file"), Map.of(), List.of("anchor"), "OFF")),
                snapshot(SettingsSourceKind.USER, settings(null, null, List.of(), List.of(), Map.of(), List.of(), null))));

        var effective = result.effectiveSettings().orElseThrow();
        assertThat(effective.modelName().orElseThrow().value()).isEqualTo("default");
        assertThat(effective.enabledTools().orElseThrow().value()).isEmpty();
        assertThat(effective.compactInstructions()).extracting(item -> item.instruction()).containsExactly("anchor");
    }

    @Test
    void replacesAndRemovesToolConfigurationsWithTombstoneProvenance() {
        SettingsResolution result = resolver.resolve(List.of(
                snapshot(SettingsSourceKind.DEFAULTS, settings(null, null, List.of(), null,
                        Map.of("read_file", new Replacement(new JsonObject(Map.of("maxLines", 10)))), List.of(), null)),
                snapshot(SettingsSourceKind.USER, settings(null, null, List.of(), null,
                        Map.of("read_file", new Replacement(new JsonObject(Map.of("maxLines", 20)))), List.of(), null)),
                snapshot(SettingsSourceKind.PROJECT_SHARED, settings(null, null, List.of(), null,
                        Map.of("read_file", new Removal()), List.of(), null))));

        var configuration = result.effectiveSettings().orElseThrow().toolConfigurations().get("read_file");
        assertThat(configuration.declaration()).isInstanceOf(Removal.class);
        assertThat(configuration.provenance().operation()).isEqualTo(SettingOperation.REMOVE);
        assertThat(configuration.provenance().sourceId().kind()).isEqualTo(SettingsSourceKind.PROJECT_SHARED);
    }

    @Test
    void replacesRulesAtOriginalPositionAndKeepsDeletionTombstone() {
        DeclaredPermissionRule first = rule("first", "ALLOW");
        DeclaredPermissionRule second = rule("second", "ASK");
        SettingsResolution result = resolver.resolve(List.of(
                snapshot(SettingsSourceKind.DEFAULTS, settings(null, null, List.of(first, second), null, Map.of(), List.of(), null)),
                snapshot(SettingsSourceKind.USER, settings(null, null, List.of(rule("first", "DENY")), null, Map.of(), List.of(), null)),
                snapshot(SettingsSourceKind.PROJECT_SHARED, settings(null, null, List.of(new DeclaredPermissionRuleRemoval("second")), null, Map.of(), List.of(), null))));

        var effective = result.effectiveSettings().orElseThrow();
        assertThat(effective.permissionRules()).extracting(rule -> rule.definition().ruleId()).containsExactly("first");
        assertThat(effective.permissionRules().getFirst().definition().decision()).isEqualTo("DENY");
        assertThat(effective.permissionRules().getFirst().provenance().operation()).isEqualTo(SettingOperation.REPLACE);
        assertThat(effective.permissionRules().getFirst().provenance().sourceId().kind()).isEqualTo(SettingsSourceKind.USER);
        assertThat(effective.removedPermissionRules()).containsKey("second");
        assertThat(effective.removedPermissionRules().get("second").operation()).isEqualTo(SettingOperation.REMOVE);
    }

    @Test
    void readdingRemovedRuleClearsTombstoneAndUsesNewStablePosition() {
        SettingsResolution result = resolver.resolve(List.of(
                snapshot(SettingsSourceKind.DEFAULTS,
                        settings(null, null, List.of(rule("first", "ALLOW"), rule("second", "ASK")), null,
                                Map.of(), List.of(), null)),
                snapshot(SettingsSourceKind.USER,
                        settings(null, null, List.of(new DeclaredPermissionRuleRemoval("first")), null,
                                Map.of(), List.of(), null)),
                snapshot(SettingsSourceKind.PROJECT_SHARED,
                        settings(null, null, List.of(rule("first", "DENY")), null, Map.of(), List.of(), null))));

        var effective = result.effectiveSettings().orElseThrow();
        assertThat(effective.permissionRules()).extracting(rule -> rule.definition().ruleId())
                .containsExactly("second", "first");
        assertThat(effective.permissionRules().getLast().definition().decision()).isEqualTo("DENY");
        assertThat(effective.permissionRules().getLast().provenance().operation()).isEqualTo(SettingOperation.SET);
        assertThat(effective.permissionRules().getLast().provenance().sourceId().kind())
                .isEqualTo(SettingsSourceKind.PROJECT_SHARED);
        assertThat(effective.removedPermissionRules()).doesNotContainKey("first");
    }

    @Test
    void deduplicatesCompactInstructionsByExactUnicodeCodePoints() {
        String composed = "é";
        String decomposed = "é";
        SettingsResolution result = resolver.resolve(List.of(
                snapshot(SettingsSourceKind.DEFAULTS,
                        settings(null, null, List.of(), null, Map.of(), List.of(composed), null)),
                snapshot(SettingsSourceKind.USER,
                        settings(null, null, List.of(), null, Map.of(), List.of(decomposed, composed), null))));

        var instructions = result.effectiveSettings().orElseThrow().compactInstructions();
        assertThat(instructions).extracting(item -> item.instruction()).containsExactly(composed, decomposed);
        assertThat(instructions.getFirst().suppressedDuplicates()).hasSize(1);
        assertThat(instructions.get(1).suppressedDuplicates()).isEmpty();
    }

    @Test
    void reportsAtomicFailureForMissingRemovalOrderDuplicateAndForbiddenCliFields() {
        SettingsResolution missingRemoval = resolver.resolve(List.of(snapshot(SettingsSourceKind.DEFAULTS,
                settings(null, null, List.of(), null, Map.of(), List.of(), null)), snapshot(SettingsSourceKind.USER,
                settings(null, null, List.of(new DeclaredPermissionRuleRemoval("missing")), null, Map.of(), List.of(), null))));
        SettingsResolution wrongOrder = resolver.resolve(List.of(snapshot(SettingsSourceKind.USER,
                settings(null, null, List.of(), null, Map.of(), List.of(), null)), snapshot(SettingsSourceKind.DEFAULTS,
                settings(null, null, List.of(), null, Map.of(), List.of(), null))));
        SettingsResolution duplicate = resolver.resolve(List.of(snapshot(SettingsSourceKind.DEFAULTS,
                settings(null, null, List.of(), null, Map.of(), List.of(), null)), snapshot(SettingsSourceKind.DEFAULTS,
                settings(null, null, List.of(), null, Map.of(), List.of(), null))));
        SettingsResolution forbiddenCli = resolver.resolve(List.of(snapshot(SettingsSourceKind.CLI,
                settings(null, null, List.of(rule("rule", "ALLOW")), null, Map.of(), List.of(), null))));
        SettingsResolution forbiddenCliConfig = resolver.resolve(List.of(snapshot(SettingsSourceKind.CLI,
                settings(null, null, List.of(), null, Map.of("read_file", new Removal()), List.of(), null))));
        SettingsResolution sameSourceOnlyRemoval = resolver.resolve(List.of(snapshot(SettingsSourceKind.DEFAULTS,
                settings(null, null, List.of(), null, Map.of(), List.of(), null)), snapshot(SettingsSourceKind.USER,
                settings(null, null, List.of(rule("same-source", "ALLOW"),
                        new DeclaredPermissionRuleRemoval("same-source")), null, Map.of(), List.of(), null))));

        assertFailure(missingRemoval, ConfigurationDiagnosticCode.MISSING_RULE_REMOVAL);
        assertFailure(wrongOrder, ConfigurationDiagnosticCode.INVALID_SOURCE_ORDER);
        assertFailure(duplicate, ConfigurationDiagnosticCode.DUPLICATE_SOURCE);
        assertFailure(forbiddenCli, ConfigurationDiagnosticCode.FORBIDDEN_SOURCE_FIELD);
        assertFailure(forbiddenCliConfig, ConfigurationDiagnosticCode.FORBIDDEN_SOURCE_FIELD);
        assertFailure(sameSourceOnlyRemoval, ConfigurationDiagnosticCode.MISSING_RULE_REMOVAL);
    }

    @Test
    void isDefensiveRedactedAndDeterministic() {
        String selector = "private-selector";
        SettingsResolution first = resolver.resolve(List.of(snapshot(SettingsSourceKind.DEFAULTS,
                settings("private-model", null, List.of(rule("private-rule", "ALLOW", selector)), List.of("read_file"),
                        Map.of("read_file", new Replacement(new JsonObject(Map.of("endpoint", "private-endpoint")))),
                        List.of("private-anchor"), null))));
        SettingsResolution second = resolver.resolve(List.of(snapshot(SettingsSourceKind.DEFAULTS,
                settings("private-model", null, List.of(rule("private-rule", "ALLOW", selector)), List.of("read_file"),
                        Map.of("read_file", new Replacement(new JsonObject(Map.of("endpoint", "private-endpoint")))),
                        List.of("private-anchor"), null))));
        var effective = first.effectiveSettings().orElseThrow();

        assertThat(effective).isEqualTo(second.effectiveSettings().orElseThrow());
        assertThat(effective.toString()).doesNotContain("private-model", selector, "private-endpoint", "private-anchor");
        assertThat(first.toString()).doesNotContain("private-model", selector, "private-endpoint", "private-anchor");
        assertThat(effective.permissionRules().getFirst().toString()).doesNotContain(selector);
        assertThat(effective.toolConfigurations().get("read_file").toString()).doesNotContain("private-endpoint");
        assertThat(effective.compactInstructions().getFirst().toString()).doesNotContain("private-anchor");
        assertThat(effective.toolConfigurations()).isUnmodifiable();
        assertThat(effective.compactInstructions()).isUnmodifiable();
        assertThat(effective.enabledTools().orElseThrow().value()).isUnmodifiable();
    }

    private static void assertFailure(SettingsResolution result, ConfigurationDiagnosticCode code) {
        assertThat(result.effectiveSettings()).isEmpty();
        assertThat(result.diagnostics()).extracting(diagnostic -> diagnostic.code()).containsExactly(code);
    }

    private static SettingsSourceSnapshot snapshot(SettingsSourceKind kind, DeclaredSettings settings) {
        return new SettingsSourceSnapshot(new SettingsSourceId(kind, kind.name().toLowerCase()),
                new SettingsRevision("a".repeat(63) + Integer.toHexString(kind.ordinal())), settings, List.of());
    }

    private static DeclaredSettings settings(String model, String mode, List<DeclaredPermissionRule> rules, List<String> tools,
                                             Map<String, DeclaredToolConfiguration> configurations, List<String> anchors,
                                             String verbosity) {
        return new DeclaredSettings(Optional.ofNullable(model), Optional.ofNullable(mode), rules, Optional.ofNullable(tools),
                configurations, anchors, Optional.ofNullable(verbosity));
    }

    private static DeclaredPermissionRuleDefinition rule(String id, String decision) {
        return rule(id, decision, "selector");
    }

    private static DeclaredPermissionRuleDefinition rule(String id, String decision, String selector) {
        return new DeclaredPermissionRuleDefinition(id, decision, "READ_WORKSPACE", "read_file", "BUILT_IN", selector);
    }
}
