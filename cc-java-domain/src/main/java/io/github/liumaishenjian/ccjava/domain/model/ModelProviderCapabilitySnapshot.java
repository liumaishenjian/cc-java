package io.github.liumaishenjian.ccjava.domain.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 分离配置声明、运行探测与最终有效值的 Provider 能力快照。
 *
 * <p>有效值采用保守交集：配置或观测任一明确不支持即不支持；只有二者至少一方明确
 * 支持且另一方不反对时才支持，其余保持未知。</p>
 *
 * @param providerId 项目自有 Provider 标识
 * @param modelId 非敏感模型标识
 * @param configured 可信配置声明
 * @param observed 实际探测证据
 * @param effective 保守收敛结果
 * @since 0.1.0
 */
public record ModelProviderCapabilitySnapshot(
        String providerId,
        String modelId,
        Map<ModelCapability, CapabilitySupport> configured,
        Map<ModelCapability, CapabilitySupport> observed,
        Map<ModelCapability, CapabilitySupport> effective) {

    /** 校验 Provider/Model identity 并验证 effective 映射等于证据的保守收敛结果。 */
    public ModelProviderCapabilitySnapshot {
        providerId = requireText(providerId, "providerId");
        modelId = requireText(modelId, "modelId");
        configured = copy(configured);
        observed = copy(observed);
        effective = copy(effective);
        for (ModelCapability capability : ModelCapability.values()) {
            CapabilitySupport expected = resolve(configured.get(capability), observed.get(capability));
            if (effective.get(capability) != expected) {
                throw new IllegalArgumentException("effective 与配置/观测证据不一致");
            }
        }
    }

    /**
     * 由配置和观测证据生成保守快照。
     *
     * @param providerId 项目自有 Provider 标识
     * @param modelId 非敏感模型标识
     * @param configured 可信配置声明
     * @param observed 实际探测证据
     * @return 包含完整 effective 映射的不可变快照
     */
    public static ModelProviderCapabilitySnapshot resolve(
            String providerId,
            String modelId,
            Map<ModelCapability, CapabilitySupport> configured,
            Map<ModelCapability, CapabilitySupport> observed) {
        Map<ModelCapability, CapabilitySupport> configuredCopy = copy(configured);
        Map<ModelCapability, CapabilitySupport> observedCopy = copy(observed);
        EnumMap<ModelCapability, CapabilitySupport> effective = new EnumMap<>(ModelCapability.class);
        for (ModelCapability capability : ModelCapability.values()) {
            effective.put(capability, resolve(configuredCopy.get(capability), observedCopy.get(capability)));
        }
        return new ModelProviderCapabilitySnapshot(
                providerId, modelId, configuredCopy, observedCopy, effective);
    }

    /**
     * 返回能力是否已由有效快照明确证明。
     *
     * @param capability 要检查的能力维度
     * @return effective 证据明确为支持时返回 {@code true}
     */
    public boolean supports(ModelCapability capability) {
        return effective.get(Objects.requireNonNull(capability, "capability 不能为空"))
                == CapabilitySupport.SUPPORTED;
    }

    private static CapabilitySupport resolve(CapabilitySupport configured, CapabilitySupport observed) {
        if (configured == CapabilitySupport.UNSUPPORTED || observed == CapabilitySupport.UNSUPPORTED) {
            return CapabilitySupport.UNSUPPORTED;
        }
        if (configured == CapabilitySupport.SUPPORTED || observed == CapabilitySupport.SUPPORTED) {
            return CapabilitySupport.SUPPORTED;
        }
        return CapabilitySupport.UNKNOWN;
    }

    private static Map<ModelCapability, CapabilitySupport> copy(
            Map<ModelCapability, CapabilitySupport> source) {
        Objects.requireNonNull(source, "能力映射不能为空");
        EnumMap<ModelCapability, CapabilitySupport> copy = new EnumMap<>(ModelCapability.class);
        for (ModelCapability capability : ModelCapability.values()) {
            copy.put(capability, Objects.requireNonNullElse(
                    source.get(capability), CapabilitySupport.UNKNOWN));
        }
        return Map.copyOf(copy);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(field + " 非法");
        }
        return value;
    }
}
