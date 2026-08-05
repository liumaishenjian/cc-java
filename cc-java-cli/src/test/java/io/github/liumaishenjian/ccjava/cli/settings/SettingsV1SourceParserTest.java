package io.github.liumaishenjian.ccjava.cli.settings;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredPermissionRuleDefinition;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredPermissionRuleRemoval;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceId;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceKind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SettingsV1SourceParserTest {
    private static final SettingsSourceId SOURCE = new SettingsSourceId(SettingsSourceKind.PROJECT_SHARED, "project-settings");
    private static final SettingsV1SourceParser PARSER = new SettingsV1SourceParser(Map.of(
            "read_file", Set.of("maxLines", "includeHidden"),
            "search", Set.of("maxResults")));

    @Test
    void acceptsMinimalAndCompleteSourcesInDeclaredOrder() {
        SettingsV1SourceParser.ParseResult minimal = parse("{\"schemaVersion\":1}");
        SettingsV1SourceParser.ParseResult complete = parse("""
                {"schemaVersion":1,"model":{"name":"small-model"},"permission":{"mode":"PLAN","rules":[
                {"ruleId":"read-docs","decision":"ALLOW","effect":"READ_WORKSPACE","tool":"read_file","toolSource":"BUILT_IN","selector":"docs/**"},
                {"remove":"old-rule"}]},"tools":{"enabled":["read_file","search"],"config":{"read_file":{"maxLines":100,"includeHidden":false}}},"context":{"compactInstructions":["preserve facts","keep tests"]},"diagnostics":{"verbosity":"DETAIL"}}
                """);

        assertThat(minimal.snapshot()).isPresent();
        assertThat(minimal.diagnostics()).isEmpty();
        assertThat(complete.snapshot()).isPresent();
        assertThat(complete.snapshot().orElseThrow().declaredValues().enabledTools().orElseThrow())
                .containsExactly("read_file", "search");
        assertThat(complete.snapshot().orElseThrow().declaredValues().toolConfigurations().get("read_file").values())
                .containsEntry("maxLines", java.math.BigInteger.valueOf(100)).containsEntry("includeHidden", false);
        assertThat(complete.snapshot().orElseThrow().declaredValues().permissionRules())
                .hasSize(2)
                .element(0).isInstanceOf(DeclaredPermissionRuleDefinition.class);
        assertThat(complete.snapshot().orElseThrow().declaredValues().permissionRules())
                .element(1).isInstanceOf(DeclaredPermissionRuleRemoval.class);
    }

    @Test
    void rejectsNestedDuplicateKeysAtomically() {
        assertFailure("{\"schemaVersion\":1,\"model\":{\"name\":\"a\",\"name\":\"b\"}}", ConfigurationDiagnosticCode.DUPLICATE_KEY);
    }

    @Test
    void rejectsTrailingGarbageAndInvalidSchemaBeforeTreeMaterialization() {
        assertFailure("{\"schemaVersion\":1} unexpected", ConfigurationDiagnosticCode.MALFORMED_JSON);
        assertFailure("{\"schemaVersion\":1.0}", ConfigurationDiagnosticCode.SCHEMA_VERSION_INVALID);
    }

    @Test
    void rejectsInvalidUtf8BeforeMaterializingSource() {
        SettingsV1SourceParser.ParseResult result = PARSER.parse(SOURCE,
                new byte[] {'{', '"', (byte) 0xC3, '"', ':', '1', '}'});

        assertThat(result.snapshot()).isEmpty();
        assertThat(result.diagnostics()).singleElement()
                .extracting(diagnostic -> diagnostic.code()).isEqualTo(ConfigurationDiagnosticCode.MALFORMED_JSON);
    }

    @Test
    void requiresSchemaVersionAsFirstIntegerMember() {
        assertFailure("{\"model\":{\"name\":\"a\"},\"schemaVersion\":1}", ConfigurationDiagnosticCode.SCHEMA_VERSION_FIRST);
        assertFailure("{\"schemaVersion\":\"1\"}", ConfigurationDiagnosticCode.SCHEMA_VERSION_INVALID);
        assertFailure("{\"schemaVersion\":2}", ConfigurationDiagnosticCode.SCHEMA_VERSION_INVALID);
    }

    @Test
    void rejectsUnknownFieldsAndWrongTypesAtomically() {
        assertFailure("{\"schemaVersion\":1,\"model\":{\"unknown\":true}}", ConfigurationDiagnosticCode.UNKNOWN_FIELD);
        assertFailure("{\"schemaVersion\":1,\"tools\":{\"enabled\":\"read_file\"}}", ConfigurationDiagnosticCode.INVALID_TYPE);
        assertFailure("{\"schemaVersion\":1,\"tools\":{\"enabled\":[\"not_registered\"]}}", ConfigurationDiagnosticCode.UNSUPPORTED_TOOL);
        assertFailure("{\"schemaVersion\":1,\"tools\":{\"config\":{\"read_file\":{\"unsafe\":true}}}}", ConfigurationDiagnosticCode.UNKNOWN_FIELD);
        assertFailure("{\"schemaVersion\":1,\"permission\":{\"rules\":[{\"ruleId\":\"bad\",\"decision\":\"ALLOW\",\"effect\":\"READ_WORKSPACE\",\"tool\":\"read_file\",\"toolSource\":\"MCP\",\"selector\":\"x\"}]}}", ConfigurationDiagnosticCode.INVALID_VALUE);
    }

    @Test
    void rejectsDocumentStructureLimitsBeforeMaterializingSource() {
        assertFailure("{\"schemaVersion\":1,\"context\":{\"compactInstructions\":[" + quotedItems(17) + "]}}", ConfigurationDiagnosticCode.LIST_LIMIT);
        assertFailure("{\"schemaVersion\":1," + namedMembers(128) + "}", ConfigurationDiagnosticCode.MEMBER_LIMIT);
        assertFailure("{\"schemaVersion\":1,\"context\":{\"compactInstructions\":[\"" + "x".repeat(513) + "\"]}}", ConfigurationDiagnosticCode.INVALID_VALUE);
        assertFailure("{\"schemaVersion\":1," + nestedObject(8) + "}", ConfigurationDiagnosticCode.DEPTH_LIMIT);
        assertFailure("{\"schemaVersion\":1}" + " ".repeat(SettingsV1SourceParser.MAX_BYTES), ConfigurationDiagnosticCode.BYTE_LIMIT);
    }

    @Test
    void diagnosticRedactsSecretsEndpointsBodiesAndPaths() {
        String secret = "api-key-should-not-appear";
        SettingsV1SourceParser.ParseResult result = parse("{\"schemaVersion\":1,\"providerToken\":\"" + secret + "\",\"endpoint\":\"https://private.invalid\"}");

        assertThat(result.snapshot()).isEmpty();
        assertThat(result.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(ConfigurationDiagnosticCode.FORBIDDEN_CREDENTIAL_FIELD);
            assertThat(diagnostic.toString()).doesNotContain(secret, "https://private.invalid", "G:\\\\private");
        });
    }

    @Test
    void preservesInputOrderForListsAndRules() {
        SettingsV1SourceParser.ParseResult result = parse("""
                {"schemaVersion":1,"permission":{"rules":[
                {"ruleId":"first","decision":"ASK","effect":"READ_WORKSPACE","tool":"read_file","toolSource":"BUILT_IN","selector":"a"},
                {"ruleId":"second","decision":"DENY","effect":"READ_WORKSPACE","tool":"search","toolSource":"BUILT_IN","selector":"b"}]},"context":{"compactInstructions":["first anchor","second anchor"]}}
                """);

        assertThat(result.snapshot()).isPresent();
        assertThat(result.snapshot().orElseThrow().declaredValues().permissionRules())
                .extracting(rule -> rule.ruleId()).containsExactly("first", "second");
        assertThat(result.snapshot().orElseThrow().declaredValues().compactInstructions())
                .containsExactly("first anchor", "second anchor");
    }

    private static SettingsV1SourceParser.ParseResult parse(String json) {
        return PARSER.parse(SOURCE, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertFailure(String json, ConfigurationDiagnosticCode expected) {
        SettingsV1SourceParser.ParseResult result = parse(json);
        assertThat(result.snapshot()).isEmpty();
        assertThat(result.diagnostics()).singleElement().extracting(diagnostic -> diagnostic.code()).isEqualTo(expected);
    }

    private static String quotedItems(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "\"x\""));
    }

    private static String namedMembers(int count) {
        StringBuilder members = new StringBuilder();
        for (int index = 0; index < count; index++) {
            members.append("\"field").append(index).append("\":true,");
        }
        return members.substring(0, members.length() - 1);
    }

    private static String nestedObject(int depth) {
        StringBuilder value = new StringBuilder("\"unknown\":true");
        for (int index = 0; index < depth; index++) {
            value.insert(0, "\"layer" + index + "\":{");
            value.append('}');
        }
        return value.toString();
    }
}
