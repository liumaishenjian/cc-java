package io.github.liumaishenjian.ccjava.cli.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderCatalogTest {
    @Test
    void mergesBuiltinModelOverlayWithoutChangingProviderContract() {
        ProviderDefinition baseline = ProviderCatalog.builtin("anthropic");
        ProviderCatalog catalog = new ProviderCatalog(List.of(), List.of(
                new ProviderCatalog.ModelOverride("anthropic", List.of("claude-overlay"), List.of())));

        ProviderDefinition effective = catalog.require("anthropic");
        assertThat(effective.models()).containsExactly("claude-sonnet-4-6", "claude-overlay");
        assertThat(effective.providerId()).isEqualTo(baseline.providerId());
        assertThat(effective.kind()).isEqualTo(baseline.kind());
        assertThat(effective.baseUri()).isEqualTo(baseline.baseUri());
        assertThat(effective.apiVariant()).isEqualTo(baseline.apiVariant());
        assertThat(effective.defaultModelId()).isEqualTo(baseline.defaultModelId());
        assertThat(effective.staticHeaders()).isEqualTo(baseline.staticHeaders());
        assertThat(effective.connectTimeout()).isEqualTo(baseline.connectTimeout());
        assertThat(effective.requestTimeout()).isEqualTo(baseline.requestTimeout());
    }

    @Test
    void rejectsDuplicateOverlayAndRemovingBuiltinDefault() {
        ProviderCatalog.ModelOverride empty = new ProviderCatalog.ModelOverride(
                "anthropic", List.of(), List.of());
        assertThatThrownBy(() -> new ProviderCatalog(List.of(), List.of(empty, empty)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PROVIDER_DEFINITION_INVALID");
        assertThatThrownBy(() -> new ProviderCatalog(List.of(), List.of(
                new ProviderCatalog.ModelOverride("anthropic", List.of(), List.of("claude-sonnet-4-6")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PROVIDER_DEFINITION_INVALID");
    }

    @Test
    void rejectsUnknownProviderWithExistingTemporaryCode() {
        assertThatThrownBy(() -> new ProviderCatalog(List.of()).require("absent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PROVIDER_UNKNOWN");
    }
}
