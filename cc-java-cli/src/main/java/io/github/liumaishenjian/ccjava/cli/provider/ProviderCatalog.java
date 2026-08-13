package io.github.liumaishenjian.ccjava.cli.provider;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 合并代码注册官方 Provider、受限模型 overlay 与用户 custom definition 的非秘密 catalog。
 *
 * <p>用户定义不得覆盖 built-in；built-in overlay 只能改变模型 identity 集合，不能改变 origin、kind、
 * API variant、Header、timeout 或代码注册的默认模型。返回顺序固定，避免选择受 map 顺序影响。</p>
 */
public final class ProviderCatalog {
    private final Map<String, ProviderDefinition> definitions;

    /**
     * 创建未应用 built-in 模型 overlay 的兼容 catalog。
     *
     * @param customDefinitions 要与内置定义合并的自定义 Provider 定义
     */
    public ProviderCatalog(List<ProviderDefinition> customDefinitions) {
        this(customDefinitions, List.of());
    }

    /**
     * 创建包含官方 Provider、built-in 模型 overlay 与 custom definitions 的 catalog。
     *
     * @param customDefinitions 要与内置定义合并的自定义 Provider 定义
     * @param modelOverrides 应用于内置 Provider 的模型 identity overlay
     */
    public ProviderCatalog(List<ProviderDefinition> customDefinitions, List<ModelOverride> modelOverrides) {
        LinkedHashMap<String, ProviderDefinition> values = new LinkedHashMap<>();
        register(values, builtinAnthropic());
        register(values, builtinOpenRouter());
        Set<String> overriddenProviders = new HashSet<>();
        for (ModelOverride override : Objects.requireNonNull(modelOverrides, "modelOverrides 不能为空")) {
            ProviderDefinition baseline = values.get(override.providerId());
            if (baseline == null || !isBuiltinId(override.providerId())
                    || !overriddenProviders.add(override.providerId())) throw invalid();
            values.put(override.providerId(), apply(baseline, override));
        }
        for (ProviderDefinition definition : Objects.requireNonNull(customDefinitions, "customDefinitions 不能为空")) {
            if (definition.kind() != ProviderDefinition.Kind.OPENAI_COMPATIBLE
                    || values.containsKey(definition.providerId())) throw invalid();
            register(values, definition);
        }
        this.definitions = Map.copyOf(values);
    }

    /**
     * 返回 Provider，未知时显式失败。
     *
     * @param id 要查找的 Provider 标识
     * @return 与标识对应的 Provider 定义
     */
    public ProviderDefinition require(String id) {
        ProviderDefinition value = definitions.get(id);
        if (value == null) throw new IllegalArgumentException("PROVIDER_UNKNOWN");
        return value;
    }

    /**
     * 返回稳定排序、不可变的 definition 列表。
     *
     * @return 按 Provider 标识排序的不可变定义列表
     */
    public List<ProviderDefinition> list() {
        ArrayList<ProviderDefinition> result = new ArrayList<>(definitions.values());
        result.sort(Comparator.comparing(ProviderDefinition::providerId));
        return List.copyOf(result);
    }

    private static ProviderDefinition apply(ProviderDefinition baseline, ModelOverride override) {
        LinkedHashMap<String, String> models = new LinkedHashMap<>();
        baseline.models().forEach(value -> models.put(value, value));
        override.removedModels().forEach(models::remove);
        override.addedModels().forEach(value -> models.put(value, value));
        if (!models.containsKey(baseline.defaultModelId()) || models.size() > 128) throw invalid();
        return new ProviderDefinition(baseline.providerId(), baseline.kind(), baseline.displayName(), baseline.baseUri(),
                baseline.apiVariant(), List.copyOf(models.values()), baseline.defaultModelId(), baseline.staticHeaders(),
                baseline.connectTimeout(), baseline.requestTimeout());
    }

    private static void register(Map<String, ProviderDefinition> values, ProviderDefinition definition) {
        if (values.putIfAbsent(definition.providerId(), definition) != null) throw invalid();
    }

    /**
     * 返回 identity 是否由代码注册，用户文件不得覆盖。
     *
     * @param providerId 待判断的 Provider 标识
     * @return 标识由代码内置注册时为 {@code true}
     */
    public static boolean isBuiltinId(String providerId) {
        return "anthropic".equals(providerId) || "openrouter".equals(providerId);
    }

    /**
     * 返回指定 built-in 的代码注册模型，不应用用户 overlay。
     *
     * @param providerId 内置 Provider 标识
     * @return 对应的代码注册基线定义
     */
    public static ProviderDefinition builtin(String providerId) {
        return switch (providerId) {
            case "anthropic" -> builtinAnthropic();
            case "openrouter" -> builtinOpenRouter();
            default -> throw new IllegalArgumentException("PROVIDER_UNKNOWN");
        };
    }

    private static ProviderDefinition builtinAnthropic() {
        return new ProviderDefinition("anthropic", ProviderDefinition.Kind.ANTHROPIC, "Anthropic",
                URI.create("https://api.anthropic.com"), ProviderDefinition.ApiVariant.ANTHROPIC_MESSAGES,
                List.of("claude-sonnet-4-6"), "claude-sonnet-4-6", Map.of(),
                Duration.ofSeconds(10), Duration.ofSeconds(300));
    }

    private static ProviderDefinition builtinOpenRouter() {
        return new ProviderDefinition("openrouter", ProviderDefinition.Kind.OPENROUTER, "OpenRouter",
                URI.create("https://openrouter.ai/api"), ProviderDefinition.ApiVariant.OPENROUTER_CHAT_COMPLETIONS,
                List.of("anthropic/claude-sonnet-4.6"), "anthropic/claude-sonnet-4.6", Map.of(),
                Duration.ofSeconds(10), Duration.ofSeconds(300));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("PROVIDER_DEFINITION_INVALID");
    }

    /**
     * 单个 built-in Provider 的模型 identity overlay。
     *
     * @param providerId 只能是代码注册的 built-in identity
     * @param addedModels 相对代码基线显式增加的模型 ID
     * @param removedModels 相对代码基线显式隐藏的模型 ID
     */
    public record ModelOverride(String providerId, List<String> addedModels, List<String> removedModels) {
        /** 校验 identity、数量、重复与 add/remove 冲突。 */
        public ModelOverride {
            if (!isBuiltinId(providerId)) throw invalid();
            addedModels = checkedModels(addedModels);
            removedModels = checkedModels(removedModels);
            Set<String> overlap = new HashSet<>(addedModels);
            overlap.retainAll(removedModels);
            if (!overlap.isEmpty() || addedModels.size() + removedModels.size() > 128) throw invalid();
        }

        private static List<String> checkedModels(List<String> values) {
            List<String> copy = List.copyOf(Objects.requireNonNull(values));
            Set<String> unique = new HashSet<>();
            for (String value : copy) {
                // 复用 ProviderDefinition 对 model identity 的精确边界校验。
                new ProviderDefinition("validation", ProviderDefinition.Kind.OPENAI_COMPATIBLE, "validation",
                        URI.create("https://example.invalid"), ProviderDefinition.ApiVariant.OPENAI_CHAT_COMPLETIONS,
                        List.of(value), value, Map.of(), Duration.ofSeconds(1), Duration.ofSeconds(1));
                if (!unique.add(value)) throw invalid();
            }
            return copy;
        }
    }
}
