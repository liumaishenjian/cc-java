package io.github.liumaishenjian.ccjava.core.governance;

import java.util.Objects;
import java.util.Optional;

/**
 * 以当前可信策略或 LKG 收敛 Managed Policy；安全项无可信值时 Fail Closed。
 *
 * @since 0.1.0
 */
public final class ManagedPolicyResolver {
    /** 创建无状态的 Managed Policy 可信来源选择器。 */
    public ManagedPolicyResolver() { }

    /**
     * 按 current、LKG、fail-closed 顺序选择可执行策略。
     *
     * @param current 当前策略解析结果
     * @param lkg last-known-good 策略解析结果
     * @param sourceDeclared 固定机器来源是否声明了 current
     * @param securityRelevant 损坏配置是否影响安全边界
     * @return 可执行策略及其来源或失败安全状态
     */
    public ManagedPolicyResolution resolve(Optional<ManagedPolicyValue> current, Optional<ManagedPolicyValue> lkg, boolean sourceDeclared, boolean securityRelevant) {
        Objects.requireNonNull(current); Objects.requireNonNull(lkg);
        Optional<ManagedPolicyValue> trustedCurrent = current.filter(v -> v.provenance().trusted());
        if (trustedCurrent.isPresent()) return new ManagedPolicyResolution(trustedCurrent, ManagedPolicyStatus.CURRENT);
        Optional<ManagedPolicyValue> trustedLkg = lkg.filter(v -> v.provenance().trusted() && v.provenance().lastKnownGood());
        if (trustedLkg.isPresent()) return new ManagedPolicyResolution(trustedLkg, ManagedPolicyStatus.LKG);
        if (sourceDeclared && securityRelevant) return new ManagedPolicyResolution(Optional.empty(), ManagedPolicyStatus.FAIL_CLOSED);
        return new ManagedPolicyResolution(Optional.empty(), sourceDeclared ? ManagedPolicyStatus.INVALID_IGNORED : ManagedPolicyStatus.ABSENT);
    }
}
