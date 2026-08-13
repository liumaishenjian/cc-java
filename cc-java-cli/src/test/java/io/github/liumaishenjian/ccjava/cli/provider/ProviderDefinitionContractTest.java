package io.github.liumaishenjian.ccjava.cli.provider;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderDefinitionContractTest {
    @Test
    void rejectsAuthenticationHeadersCaseInsensitivelyAndDuplicateNormalizedNames() {
        for (String name : List.of("Authorization", "proxy-authorization", "X-API-Key", "api-key", "Cookie", "set-cookie")) {
            assertThatThrownBy(() -> definition(Map.of(name, "value"), List.of("model"), "model"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        Map<String, String> duplicate = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        duplicate.put("X-Test", "a"); duplicate.put("x-test", "b");
        // TreeMap 已折叠重复；用自定义 map 保留两个大小写键。
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("X-Test", "a"); values.put("x-test", "b");
        assertThatThrownBy(() -> definition(values, List.of("model"), "model"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsModelHeaderTimeoutAndUriCeilings() {
        assertThatThrownBy(() -> definition(Map.of(), List.of(" control\n"), " control\n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definition(Map.of(), List.of("m".repeat(1025)), "m".repeat(1025)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderDefinition("custom", ProviderDefinition.Kind.OPENAI_COMPATIBLE,
                "Display", URI.create("https://user@example.test/v1?query=x"),
                ProviderDefinition.ApiVariant.OPENAI_CHAT_COMPLETIONS, List.of("m"), "m", Map.of(),
                Duration.ofSeconds(1), Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderDefinition("custom", ProviderDefinition.Kind.OPENAI_COMPATIBLE,
                "Display", URI.create("https://example.test/v1"),
                ProviderDefinition.ApiVariant.OPENAI_CHAT_COMPLETIONS, List.of("m"), "m", Map.of(),
                Duration.ofSeconds(31), Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCatalogCountDuplicateModelsAndMissingDefault() {
        List<String> tooMany = java.util.stream.IntStream.range(0, 129).mapToObj(i -> "model-" + i).toList();
        assertThatThrownBy(() -> definition(Map.of(), tooMany, "model-0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definition(Map.of(), List.of("same", "same"), "same"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definition(Map.of(), List.of("known"), "absent"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ProviderDefinition definition(Map<String, String> headers, List<String> models, String defaultModel) {
        return new ProviderDefinition("custom", ProviderDefinition.Kind.OPENAI_COMPATIBLE, "Display",
                URI.create("https://example.test/v1"), ProviderDefinition.ApiVariant.OPENAI_CHAT_COMPLETIONS,
                models, defaultModel, headers, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
}
