package io.github.liumaishenjian.ccjava.domain.governance;

import java.util.Objects;

/**
 * 可投影到 stable protocol capability negotiation 的 Feature Gate。
 *
 * @param id 项目自有稳定 ID
 * @param enabled 是否启用
 * @param stability 稳定性
 * @since 0.1.0
 */
public record FeatureGate(String id, boolean enabled, FeatureStability stability) {
    /** 校验 gate identity 并冻结稳定性。 */
    public FeatureGate {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(stability, "stability 不能为空");
        if (!id.matches("[a-z][a-z0-9.-]{0,63}")) {
            throw new IllegalArgumentException("feature id 非法");
        }
    }
}
