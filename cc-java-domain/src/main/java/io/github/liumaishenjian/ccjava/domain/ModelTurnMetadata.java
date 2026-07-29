package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 模型回合的 Provider-neutral 元数据。
 *
 * @param finishReason Provider 明确返回或 Adapter 映射的结束原因
 * @param usage Provider 明确返回 Usage 时存在；缺失时为空
 * @param providerModel Provider 响应中的实际模型名；缺失时为空
 * @since 0.1.0
 */
public record ModelTurnMetadata(
        ModelFinishReason finishReason,
        Optional<ModelUsage> usage,
        Optional<String> providerModel) {

    /**
     * 校验 Optional 容器并规范化模型名。
     */
    public ModelTurnMetadata {
        finishReason = Objects.requireNonNull(finishReason, "finishReason 不能为空");
        usage = Objects.requireNonNull(usage, "usage 不能为空");
        providerModel = Objects.requireNonNull(providerModel, "providerModel 不能为空")
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }

    /**
     * 返回 Provider 没有提供可用元数据时的显式值。
     *
     * @return 未知结束原因且其他字段为空
     */
    public static ModelTurnMetadata unknown() {
        return new ModelTurnMetadata(
                ModelFinishReason.UNKNOWN,
                Optional.empty(),
                Optional.empty());
    }
}
