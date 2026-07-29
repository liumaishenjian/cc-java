package io.github.liumaishenjian.ccjava.model.springai.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

import static io.github.liumaishenjian.ccjava.model.springai.config.ProviderConfigurationException.Code.INVALID_BASE_URL;
import static io.github.liumaishenjian.ccjava.model.springai.config.ProviderConfigurationException.Code.INVALID_MODEL;
import static io.github.liumaishenjian.ccjava.model.springai.config.ProviderConfigurationException.Code.REQUIRED_VALUE_MISSING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderSettingsLoaderTest {

    Path repositoryRoot;

    private final ProviderSettingsLoader loader = new ProviderSettingsLoader();

    @BeforeEach
    void createRepositoryFixture() throws IOException {
        repositoryRoot = Path.of("target", "provider-settings-test", UUID.randomUUID().toString())
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(repositoryRoot);
    }

    @AfterEach
    void deleteRepositoryFixture() throws IOException {
        Path fixtureRoot = Path.of("target", "provider-settings-test").toAbsolutePath().normalize();
        if (repositoryRoot == null || !repositoryRoot.startsWith(fixtureRoot) || !Files.exists(repositoryRoot)) {
            return;
        }
        try (var paths = Files.walk(repositoryRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void loadsGitIgnoredLocalPropertiesAndRedactsApiKey() throws IOException {
        writeConfig("""
                openai.base-url=https://gateway.example.test/v1
                openai.api-key=secret-local-key
                openai.model=test-model
                """);

        OpenAiCompatibleSettings settings = loader.load(repositoryRoot, Map.of());

        assertThat(settings.baseUrl()).hasToString("https://gateway.example.test/v1");
        assertThat(settings.apiKey()).isEqualTo("secret-local-key");
        assertThat(settings.model()).isEqualTo("test-model");
        assertThat(settings.toString())
                .contains("apiKey=<redacted>")
                .doesNotContain("secret-local-key");
    }

    @Test
    void environmentOverridesLocalFileWithoutAppearingInDiagnostics() throws IOException {
        writeConfig("""
                openai.base-url=https://file.example.test
                openai.api-key=file-secret
                openai.model=file-model
                """);
        Map<String, String> environment = Map.of(
                ProviderSettingsLoader.BASE_URL_ENV, "https://env.example.test",
                ProviderSettingsLoader.API_KEY_ENV, "environment-secret",
                ProviderSettingsLoader.MODEL_ENV, "environment-model"
        );

        OpenAiCompatibleSettings settings = loader.load(repositoryRoot, environment);

        assertThat(settings.baseUrl()).hasToString("https://env.example.test");
        assertThat(settings.apiKey()).isEqualTo("environment-secret");
        assertThat(settings.model()).isEqualTo("environment-model");
        assertThat(settings.toString()).doesNotContain("environment-secret");
    }

    @Test
    void reportsMissingKeyWithoutIncludingAnyOtherValue() throws IOException {
        writeConfig("""
                openai.base-url=https://gateway.example.test
                openai.model=test-model
                """);

        assertThatThrownBy(() -> loader.load(repositoryRoot, Map.of()))
                .isInstanceOfSatisfying(ProviderConfigurationException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(REQUIRED_VALUE_MISSING);
                    assertThat(exception.getMessage())
                            .contains("openai.api-key")
                            .doesNotContain("gateway.example.test")
                            .doesNotContain("test-model");
                });
    }

    @Test
    void rejectsBaseUrlCredentialsWithoutEchoingTheUrl() throws IOException {
        writeConfig("""
                openai.base-url=https://user:password@gateway.example.test
                openai.api-key=secret-key
                openai.model=test-model
                """);

        assertThatThrownBy(() -> loader.load(repositoryRoot, Map.of()))
                .isInstanceOfSatisfying(ProviderConfigurationException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(INVALID_BASE_URL);
                    assertThat(exception.getMessage())
                            .doesNotContain("password")
                            .doesNotContain("secret-key");
                });
    }

    @Test
    void appliesValidatedModelOverrideWithoutExposingSecret() {
        OpenAiCompatibleSettings original = new OpenAiCompatibleSettings(
                "https://gateway.example.test",
                "secret-key",
                "file-model");

        OpenAiCompatibleSettings overridden = original.withModel("cli-model");

        assertThat(overridden.baseUrl()).isEqualTo(original.baseUrl());
        assertThat(overridden.apiKey()).isEqualTo("secret-key");
        assertThat(overridden.model()).isEqualTo("cli-model");
        assertThat(overridden.toString()).doesNotContain("secret-key");
        assertThatThrownBy(() -> original.withModel("bad\nmodel"))
                .isInstanceOfSatisfying(
                        ProviderConfigurationException.class,
                        exception -> assertThat(exception.code()).isEqualTo(INVALID_MODEL));
    }

    private void writeConfig(String content) throws IOException {
        Path config = repositoryRoot.resolve(ProviderSettingsLoader.LOCAL_CONFIG_PATH);
        Files.createDirectories(config.getParent());
        Files.writeString(config, content, StandardCharsets.UTF_8);
    }
}
