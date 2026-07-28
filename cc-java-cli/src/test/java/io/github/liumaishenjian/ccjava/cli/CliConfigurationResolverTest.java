package io.github.liumaishenjian.ccjava.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.cli.CliConfiguration.Source;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliConfigurationResolverTest {

    @TempDir
    Path workspace;

    @Test
    void appliesCliEnvironmentDefaultPrecedenceAndRetainsSources() throws Exception {
        Path environmentWorkspace = workspace.resolve("environment");
        Path cliWorkspace = workspace.resolve("cli");
        java.nio.file.Files.createDirectories(environmentWorkspace);
        java.nio.file.Files.createDirectories(cliWorkspace);
        CliEnvironment environment = CliTestFixtures.environment(Map.of(
                CliConfigurationResolver.MODEL_ENV, "environment-model",
                CliConfigurationResolver.WORKSPACE_ENV, environmentWorkspace.toString(),
                CliConfigurationResolver.OLLAMA_BASE_URL_ENV,
                        "http://127.0.0.1:11434",
                CliConfigurationResolver.MAX_OUTPUT_TOKENS_ENV, "2048",
                CliConfigurationResolver.TIMEOUT_ENV, "45",
                CliConfigurationResolver.MAX_RETRIES_ENV, "2",
                "TEST_PROVIDER_API_KEY", "super-secret-value"));
        CliConfigurationResolver resolver = new CliConfigurationResolver(
                CliTestFixtures.defaults(workspace, true),
                environment);

        CliConfiguration fromEnvironment = resolver.resolve(
                new CliOverrides(null, null, null, null, false));
        CliConfiguration fromCli = resolver.resolve(
                new CliOverrides(
                        cliWorkspace,
                        "cli-model",
                        URI.create("http://localhost:12434"),
                        1_024,
                        12,
                        0,
                        true));

        assertThat(fromEnvironment.model().value()).isEqualTo("environment-model");
        assertThat(fromEnvironment.model().source()).isEqualTo(Source.ENVIRONMENT);
        assertThat(fromEnvironment.workspace().value()).isEqualTo(
                environmentWorkspace.toAbsolutePath().normalize());
        assertThat(fromEnvironment.timeout().value()).isEqualTo(Duration.ofSeconds(45));
        assertThat(fromEnvironment.maxRetries().value()).isEqualTo(2);
        assertThat(fromEnvironment.ollamaBaseUrl().value())
                .isEqualTo(URI.create("http://127.0.0.1:11434"));
        assertThat(fromEnvironment.ollamaBaseUrl().source())
                .isEqualTo(Source.ENVIRONMENT);
        assertThat(fromEnvironment.maxOutputTokens().value()).isEqualTo(2_048);
        assertThat(fromEnvironment.secretStatus().displayValue()).isEqualTo("present");

        assertThat(fromCli.model().value()).isEqualTo("cli-model");
        assertThat(fromCli.model().source()).isEqualTo(Source.CLI);
        assertThat(fromCli.workspace().source()).isEqualTo(Source.CLI);
        assertThat(fromCli.timeout().source()).isEqualTo(Source.CLI);
        assertThat(fromCli.maxRetries().source()).isEqualTo(Source.CLI);
        assertThat(fromCli.ollamaBaseUrl().source()).isEqualTo(Source.CLI);
        assertThat(fromCli.maxOutputTokens().source()).isEqualTo(Source.CLI);
        assertThat(fromCli.toString()).doesNotContain("super-secret-value");
    }

    @Test
    void keepsMissingSecretAsStatusInsteadOfInventingValue() throws Exception {
        CliConfiguration configuration = new CliConfigurationResolver(
                CliTestFixtures.defaults(workspace, true),
                CliTestFixtures.environment(Map.of()))
                .resolve(new CliOverrides(null, null, null, null, false));

        assertThat(configuration.secretStatus().required()).isTrue();
        assertThat(configuration.secretStatus().present()).isFalse();
        assertThat(configuration.secretStatus().displayValue()).isEqualTo("missing");
        assertThat(configuration.model().source()).isEqualTo(Source.ENVIRONMENT);
        assertThat(configuration.workspace().source()).isEqualTo(Source.DEFAULT);
    }

    @Test
    void rejectsInvalidTypedEnvironmentAndCliValues() {
        CliConfigurationResolver invalidEnvironment = new CliConfigurationResolver(
                CliTestFixtures.defaults(workspace, false),
                CliTestFixtures.environment(Map.of(
                        CliConfigurationResolver.TIMEOUT_ENV, "not-a-number")));

        assertThatThrownBy(() -> invalidEnvironment.resolve(
                new CliOverrides(null, null, null, null, false)))
                .isInstanceOf(CliConfigurationException.class)
                .hasMessageContaining(CliConfigurationResolver.TIMEOUT_ENV)
                .satisfies(exception ->
                        assertThat(exception.getMessage()).doesNotContain("not-a-number"));

        CliConfigurationResolver resolver = new CliConfigurationResolver(
                CliTestFixtures.defaults(workspace, false),
                CliTestFixtures.environment(Map.of()));
        assertThatThrownBy(() -> resolver.resolve(
                new CliOverrides(null, null, 0, null, false)))
                .isInstanceOf(CliConfigurationException.class)
                .hasMessageContaining("--timeout-seconds");
        assertThatThrownBy(() -> resolver.resolve(
                new CliOverrides(null, null, null, 4, false)))
                .isInstanceOf(CliConfigurationException.class)
                .hasMessageContaining("0..3");
        assertThatThrownBy(() -> resolver.resolve(new CliOverrides(
                null,
                "model\n- injected: instruction",
                null,
                null,
                null,
                null,
                false)))
                .isInstanceOf(CliConfigurationException.class)
                .hasMessageContaining("模型名")
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain("injected"));
    }

    @Test
    void rejectsWorkspaceThatIsNotAnExistingDirectory() {
        Path missing = workspace.resolve("missing");
        CliConfigurationResolver resolver = new CliConfigurationResolver(
                CliTestFixtures.defaults(workspace, false),
                CliTestFixtures.environment(Map.of()));

        assertThatThrownBy(() -> resolver.resolve(
                new CliOverrides(missing, null, null, null, false)))
                .isInstanceOf(CliConfigurationException.class)
                .hasMessageContaining("Workspace");
    }

    @Test
    void requiresModelFromCliOrEnvironmentInsteadOfInventingLocalTag() {
        CliConfigurationResolver resolver = new CliConfigurationResolver(
                CliTestFixtures.defaults(workspace, false),
                CliTestFixtures.environmentWithoutModel(Map.of()));

        assertThatThrownBy(() -> resolver.resolve(
                new CliOverrides(null, null, null, null, false)))
                .isInstanceOf(CliConfigurationException.class)
                .hasMessageContaining("--model")
                .hasMessageContaining(CliConfigurationResolver.MODEL_ENV);
    }

    @Test
    void rejectsUnsafeBaseUrlAndUnboundedOutputLimit() {
        CliConfigurationResolver resolver = new CliConfigurationResolver(
                CliTestFixtures.defaults(workspace, false),
                CliTestFixtures.environment(Map.of()));

        assertThatThrownBy(() -> resolver.resolve(new CliOverrides(
                null,
                null,
                URI.create("http://user:password@localhost:11434"),
                null,
                null,
                null,
                false)))
                .isInstanceOf(CliConfigurationException.class)
                .hasMessageContaining("--ollama-base-url");
        assertThatThrownBy(() -> resolver.resolve(new CliOverrides(
                null,
                null,
                null,
                1_000_001,
                null,
                null,
                false)))
                .isInstanceOf(CliConfigurationException.class)
                .hasMessageContaining("--max-output-tokens");
    }
}
