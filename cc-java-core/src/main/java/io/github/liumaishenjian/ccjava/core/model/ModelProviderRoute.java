package io.github.liumaishenjian.ccjava.core.model;

import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.domain.model.ModelProviderCapabilitySnapshot;

import java.util.Objects;

/**
 * 路由器使用的 Provider 候选，绑定 Gateway 与不可变能力证据。
 *
 * @param providerId Provider 标识
 * @param gateway 实际 Gateway
 * @param capabilities 能力快照
 * @since 0.1.0
 */
public record ModelProviderRoute(
        String providerId,
        ModelGateway gateway,
        ModelProviderCapabilitySnapshot capabilities) {
    /** 校验路由身份、Gateway 与能力证据一致且完整。 */
    public ModelProviderRoute {
        providerId = Objects.requireNonNull(providerId, "providerId 不能为空");
        gateway = Objects.requireNonNull(gateway, "gateway 不能为空");
        capabilities = Objects.requireNonNull(capabilities, "capabilities 不能为空");
        if (!providerId.equals(capabilities.providerId())) {
            throw new IllegalArgumentException("route 与 capability providerId 不一致");
        }
    }
}
