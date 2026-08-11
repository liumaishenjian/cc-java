package io.github.liumaishenjian.ccjava.core.governance;

import java.util.Objects;
import java.util.Optional;

/**
 * Managed Policy current/LKG 解析后的可信选择结果。
 *
 * @param value 当前可强制执行的收窄策略；ABSENT/FAIL_CLOSED 时为空
 * @param status 策略来源或失败安全状态
 */
public record ManagedPolicyResolution(
        Optional<ManagedPolicyValue> value,
        ManagedPolicyStatus status) {
    /** 校验策略值与来源状态均已显式提供。 */
    public ManagedPolicyResolution {
        value = Objects.requireNonNull(value, "value 不能为空");
        status = Objects.requireNonNull(status, "status 不能为空");
    }
}
