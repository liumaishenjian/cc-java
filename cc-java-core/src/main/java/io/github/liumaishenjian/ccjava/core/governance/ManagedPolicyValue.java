package io.github.liumaishenjian.ccjava.core.governance;

import io.github.liumaishenjian.ccjava.domain.governance.ManagedPolicyProvenance;
import java.util.Map;
import java.util.Objects;

/**
 * 已解析的本机 Managed Policy；值只能收窄，不表达任意配置文本。
 *
 * @param deniedFeatures 管理员禁用项
 * @param requiredSandbox 是否强制 Sandbox
 * @param networkDenied 是否拒绝 JVM/进程网络
 * @param provenance provenance
 * @since 0.1.0
 */
public record ManagedPolicyValue(java.util.Set<String> deniedFeatures, boolean requiredSandbox, boolean networkDenied, ManagedPolicyProvenance provenance) {
    /** 冻结 deny 集合并校验可信来源信息存在。 */
    public ManagedPolicyValue { deniedFeatures = java.util.Set.copyOf(Objects.requireNonNull(deniedFeatures)); provenance = Objects.requireNonNull(provenance); }
}
