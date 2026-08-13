package io.github.liumaishenjian.ccjava.cli.provider;

import io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException;
import io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileSecurity;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderDefinitionStoreTest {
    @TempDir Path temporary;

    @Test
    void persistsCustomDefinitionAndDefaultWithGenerationCas() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        ProviderDefinitionStore store = new ProviderDefinitionStore(home);
        ProviderDefinition custom = custom("team", List.of("exact-model", "other"));

        ProviderDefinitionStore.Snapshot added = store.add(custom, 0, CancellationToken.none());
        ProviderDefinitionStore.Snapshot selected = store.selectDefault(Optional.of(
                new ProviderDefinitionStore.DefaultSelection("team", "exact-model")),
                added.generation(), CancellationToken.none());
        ProviderDefinitionStore.Snapshot reread = new ProviderDefinitionStore(home).snapshot(CancellationToken.none());

        assertThat(reread).isEqualTo(selected);
        assertThat(reread.catalog().list()).extracting(ProviderDefinition::providerId)
                .containsExactly("anthropic", "openrouter", "team");
        assertThatThrownBy(() -> store.remove("team", added.generation(), CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class, failure ->
                        assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_TRANSACTION_CONFLICT));
        assertThatThrownBy(() -> store.remove("team", selected.generation(), CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class, failure ->
                        assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.PROVIDER_DEFINITION_INVALID));
    }

    @Test
    void rejectsBuiltinOverrideUnknownDuplicateAndOversize() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        ProviderDefinitionStore store = new ProviderDefinitionStore(home);
        assertThatThrownBy(() -> store.add(custom("anthropic", List.of("x")), 0, CancellationToken.none()))
                .isInstanceOf(ProviderAuthException.class);

        Path root = home.resolve(".cc-java");
        store.add(custom("seed", List.of("model")), 0, CancellationToken.none());
        Path file = root.resolve("providers.v1.json");
        writeRestricted(file, """
                {"schemaVersion":1,"providers":[],"unknown":true}
                """);
        assertCorrupt(store);
        writeRestricted(file, """
                {"schemaVersion":1,"schemaVersion":1,"providers":[]}
                """);
        assertCorrupt(store);
        byte[] oversized = new byte[ProviderDefinitionStore.MAXIMUM_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) 'x');
        Files.write(file, oversized, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        assertThatThrownBy(() -> store.snapshot(CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class, failure ->
                        assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_STORE_INSECURE));
    }

    @Test
    void rejectsBuiltinInFileAndInvalidDefaultModel() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        ProviderDefinitionStore store = new ProviderDefinitionStore(home);
        store.add(custom("seed", List.of("model")), 0, CancellationToken.none());
        Path file = home.resolve(".cc-java/providers.v1.json");
        writeRestricted(file, jsonDefinition("anthropic", "OPENAI_COMPATIBLE", "model", null));
        assertCorrupt(store);
        writeRestricted(file, jsonDefinition("team", "OPENAI_COMPATIBLE", "model", "absent"));
        assertCorrupt(store);
    }

    @Test
    void persistsBuiltinModelOverlayAndReadsLegacySchemaV1() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        ProviderDefinitionStore store = new ProviderDefinitionStore(home);

        ProviderDefinitionStore.Snapshot added = store.addModel("anthropic", "claude-test-overlay", 0,
                CancellationToken.none());
        ProviderDefinitionStore.Snapshot reread = new ProviderDefinitionStore(home).snapshot(CancellationToken.none());

        assertThat(reread).isEqualTo(added);
        assertThat(reread.catalog().require("anthropic").models())
                .containsExactly("claude-sonnet-4-6", "claude-test-overlay");
        String serialized = Files.readString(home.resolve(".cc-java/providers.v1.json"));
        assertThat(serialized).contains("\"modelOverrides\"")
                .contains("\"addedModels\":[\"claude-test-overlay\"]");

        writeRestricted(home.resolve(".cc-java/providers.v1.json"),
                "{\"schemaVersion\":1,\"generation\":7,\"providers\":[]}");
        ProviderDefinitionStore.Snapshot legacy = store.snapshot(CancellationToken.none());
        assertThat(legacy.generation()).isEqualTo(7);
        assertThat(legacy.modelOverrides()).isEmpty();
    }

    @Test
    void removesAddedModelButNeverBuiltinDefaultAndEnforcesCas() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        ProviderDefinitionStore store = new ProviderDefinitionStore(home);
        ProviderDefinitionStore.Snapshot added = store.addModel("openrouter", "vendor/model", 0,
                CancellationToken.none());

        assertThatThrownBy(() -> store.removeModel("openrouter", "vendor/model", 0, CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class, failure ->
                        assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_TRANSACTION_CONFLICT));
        ProviderDefinitionStore.Snapshot removed = store.removeModel("openrouter", "vendor/model",
                added.generation(), CancellationToken.none());
        assertThat(removed.modelOverrides()).isEmpty();
        assertThatThrownBy(() -> store.removeModel("openrouter", "anthropic/claude-sonnet-4.6",
                removed.generation(), CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class, failure ->
                        assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.PROVIDER_DEFINITION_INVALID));
    }

    @Test
    void rejectsInvalidModelOverrideShapeDuplicateProviderAndArrayCeiling() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        ProviderDefinitionStore store = new ProviderDefinitionStore(home);
        store.add(custom("seed", List.of("model")), 0, CancellationToken.none());
        Path file = home.resolve(".cc-java/providers.v1.json");

        writeRestricted(file, """
                {"schemaVersion":1,"providers":[],"modelOverrides":[
                  {"providerId":"anthropic","addedModels":[],"removedModels":[],"unknown":true}]}
                """);
        assertCorrupt(store);
        writeRestricted(file, """
                {"schemaVersion":1,"providers":[],"modelOverrides":[
                  {"providerId":"anthropic","addedModels":["duplicate","duplicate"],"removedModels":[]}]}
                """);
        assertCorrupt(store);
        writeRestricted(file, """
                {"schemaVersion":1,"providers":[],"modelOverrides":[
                  {"providerId":"anthropic","addedModels":[],"removedModels":[]},
                  {"providerId":"anthropic","addedModels":[],"removedModels":[]}]}
                """);
        assertCorrupt(store);
        String models = java.util.stream.IntStream.range(0, 129)
                .mapToObj(index -> "\"model-" + index + "\"").collect(java.util.stream.Collectors.joining(","));
        writeRestricted(file, "{\"schemaVersion\":1,\"providers\":[],\"modelOverrides\":[{"
                + "\"providerId\":\"anthropic\",\"addedModels\":[" + models + "],\"removedModels\":[]}]}");
        assertCorrupt(store);
    }

    @Test
    void nonAtomicMoveNeverPublishesProviderFile() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        ProviderDefinitionStore store = new ProviderDefinitionStore(
                new RestrictedFileSecurity(home), new SecureRandom(),
                (source, target) -> { throw new java.nio.file.AtomicMoveNotSupportedException("", "", ""); });
        assertThatThrownBy(() -> store.add(custom("team", List.of("model")), 0, CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class, failure ->
                        assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_STORE_INSECURE));
        assertThat(Files.exists(home.resolve(".cc-java/providers.v1.json"))).isFalse();
    }

    private static ProviderDefinition custom(String id, List<String> models) {
        return new ProviderDefinition(id, ProviderDefinition.Kind.OPENAI_COMPATIBLE, "Test Provider",
                URI.create("https://example.test/v1"), ProviderDefinition.ApiVariant.OPENAI_CHAT_COMPLETIONS,
                models, models.getFirst(), Map.of("X-Test", "safe"), Duration.ofSeconds(3), Duration.ofSeconds(20));
    }

    private static String jsonDefinition(String id, String kind, String model, String selected) {
        String selection = selected == null ? "" : ",\"defaultSelection\":{\"providerId\":\"" + id
                + "\",\"modelId\":\"" + selected + "\"}";
        return "{\"schemaVersion\":1,\"generation\":0" + selection + ",\"providers\":[{"
                + "\"providerId\":\"" + id + "\",\"kind\":\"" + kind + "\","
                + "\"displayName\":\"Test\",\"baseUri\":\"https://example.test/v1\","
                + "\"apiVariant\":\"OPENAI_CHAT_COMPLETIONS\",\"models\":[\"" + model + "\"],"
                + "\"defaultModelId\":\"" + model + "\",\"staticHeaders\":{},"
                + "\"connectTimeoutSeconds\":3,\"requestTimeoutSeconds\":20}]}";
    }

    private static void writeRestricted(Path file, String value) throws Exception {
        if (Files.exists(file)) Files.writeString(file, value, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        else Files.writeString(file, value, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        if (Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static void assertCorrupt(ProviderDefinitionStore store) {
        assertThatThrownBy(() -> store.snapshot(CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class, failure ->
                        assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_STORE_CORRUPT));
    }
}
