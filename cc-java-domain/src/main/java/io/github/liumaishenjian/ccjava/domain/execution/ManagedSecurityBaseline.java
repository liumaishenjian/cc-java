package io.github.liumaishenjian.ccjava.domain.execution;

import java.util.Objects;

/**
 * 不可被低优先级来源放宽的受管安全底线。
 *
 * @param requiredPolicy deny-only 或 required-isolation 策略
 * @param provenance 必须为 MANAGED 的可信来源
 * @since 0.13.0
 */
public record ManagedSecurityBaseline(
        ExecutionPolicy requiredPolicy,
        PolicyProvenance provenance) {
    /** 校验策略与来源存在，并拒绝非 MANAGED provenance。 */
    public ManagedSecurityBaseline {
        requiredPolicy = Objects.requireNonNull(requiredPolicy);
        provenance = Objects.requireNonNull(provenance);
        if (provenance.kind() != PolicyProvenance.Kind.MANAGED) {
            throw new IllegalArgumentException("受管底线来源必须是 MANAGED");
        }
    }
}
