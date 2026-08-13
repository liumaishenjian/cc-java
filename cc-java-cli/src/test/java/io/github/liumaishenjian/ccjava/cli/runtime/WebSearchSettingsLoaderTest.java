package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.tools.web.WebSearchConfiguration;
import io.github.liumaishenjian.ccjava.tools.web.WebSearchProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebSearchSettingsLoaderTest {
    @TempDir Path temporary;

    @Test
    void defaultsToDisabledWithoutExternalConfiguration() {
        var configuration = WebSearchSettingsLoader.load(temporary, Map.of());
        assertThat(configuration.enabled()).isFalse();
        assertThat(configuration.endpoint()).isEmpty();
        assertThat(configuration.apiKey()).isEmpty();
    }

    @Test
    void environmentSelectsFixedExaTargetAndOverridesLocalFile() throws Exception {
        Files.createDirectories(temporary.resolve("config"));
        Files.writeString(temporary.resolve("config/provider.local.properties"), """
                web-search.enabled=false
                web-search.provider=parallel
                web-search.api-key=FILE_SECRET
                """);
        var configuration = WebSearchSettingsLoader.load(temporary, Map.of(
                "CC_JAVA_WEB_SEARCH_ENABLED", "true",
                "CC_JAVA_WEB_SEARCH_PROVIDER", "exa",
                "EXA_API_KEY", "ENV_SECRET"));
        assertThat(configuration.enabled()).isTrue();
        assertThat(configuration.provider()).isEqualTo(WebSearchProvider.EXA);
        assertThat(configuration.endpoint()).hasValue(WebSearchConfiguration.EXA_HOSTED_MCP);
        assertThat(configuration.apiKey()).hasValue("ENV_SECRET");
        assertThat(configuration.toString()).doesNotContain("mcp.exa.ai", "ENV_SECRET", "FILE_SECRET");
    }

    @Test
    void exaWorksWithoutKeyAndParallelUsesProviderSpecificEnvironmentKey() {
        var exa = WebSearchSettingsLoader.load(temporary, Map.of(
                "CC_JAVA_WEB_SEARCH_ENABLED", "true", "CC_JAVA_WEB_SEARCH_PROVIDER", "exa"));
        assertThat(exa.enabled()).isTrue();
        assertThat(exa.apiKey()).isEmpty();

        var parallel = WebSearchSettingsLoader.load(temporary, Map.of(
                "CC_JAVA_WEB_SEARCH_ENABLED", "true",
                "CC_JAVA_WEB_SEARCH_PROVIDER", "parallel",
                "PARALLEL_API_KEY", "PARALLEL_SECRET"));
        assertThat(parallel.provider()).isEqualTo(WebSearchProvider.PARALLEL);
        assertThat(parallel.endpoint()).hasValue(WebSearchConfiguration.PARALLEL_HOSTED_MCP);
        assertThat(parallel.apiKey()).hasValue("PARALLEL_SECRET");
    }

    @Test
    void missingOrUnknownProviderFailsClosedAndEndpointEnvironmentCannotOverrideTarget() {
        assertThat(WebSearchSettingsLoader.load(temporary, Map.of(
                "CC_JAVA_WEB_SEARCH_ENABLED", "true")).enabled()).isFalse();
        assertThat(WebSearchSettingsLoader.load(temporary, Map.of(
                "CC_JAVA_WEB_SEARCH_ENABLED", "true",
                "CC_JAVA_WEB_SEARCH_PROVIDER", "unknown")).enabled()).isFalse();

        var configuration = WebSearchSettingsLoader.load(temporary, Map.of(
                "CC_JAVA_WEB_SEARCH_ENABLED", "true",
                "CC_JAVA_WEB_SEARCH_PROVIDER", "exa",
                "CC_JAVA_WEB_SEARCH_ENDPOINT", "https://attacker.example/mcp"));
        assertThat(configuration.endpoint()).hasValue(WebSearchConfiguration.EXA_HOSTED_MCP);
    }
}
