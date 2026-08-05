package io.github.liumaishenjian.ccjava.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.liumaishenjian.ccjava.domain.settings.DeclaredPermissionRuleDefinition;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredSettings;
import io.github.liumaishenjian.ccjava.domain.settings.SettingValidationStatus;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsRevision;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceId;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceKind;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SettingsContractsTest {
    @Test
    void publicRuleDefinitionRejectsNulAndOversizedSelectorsIndependentOfParser() {
        assertThatIllegalArgumentException().isThrownBy(() -> rule("allowed\0path"));
        assertThatIllegalArgumentException().isThrownBy(() -> rule("x".repeat(4_097)));
    }

    @Test
    void sensitiveDeclarationsAndSnapshotsRedactTheirStringRepresentations() {
        String selector = "G:\\private\\selector";
        String compactInstruction = "compact instruction text";
        String endpoint = "https://private.invalid";
        String secret = "api-key-should-not-appear";
        DeclaredPermissionRuleDefinition rule = rule(selector);
        DeclaredSettings settings = new DeclaredSettings(Optional.of("private-model"), Optional.of("PLAN"), List.of(rule),
                Optional.of(List.of("read_file")), Map.of("read_file", new JsonObject(Map.of("endpoint", endpoint, "token", secret))),
                List.of(compactInstruction), Optional.of("DETAIL"));
        SettingsSourceSnapshot snapshot = new SettingsSourceSnapshot(
                new SettingsSourceId(SettingsSourceKind.PROJECT_SHARED, "project-settings"),
                new SettingsRevision("a".repeat(64)), settings, List.of());

        assertThat(rule.toString()).doesNotContain(selector);
        assertThat(settings.toString()).doesNotContain("private-model", endpoint, secret, compactInstruction, selector);
        assertThat(snapshot.toString()).doesNotContain("private-model", endpoint, secret, compactInstruction, selector);
    }

    @Test
    void validationStatusIsTypedAndRequired() {
        assertThat(SettingValidationStatus.VALID).isNotNull();
        assertThat(SettingValidationStatus.INVALID).isNotNull();
    }

    private static DeclaredPermissionRuleDefinition rule(String selector) {
        return new DeclaredPermissionRuleDefinition(
                "read-docs", "ALLOW", "READ_WORKSPACE", "read_file", "BUILT_IN", selector);
    }
}
