package io.github.liumaishenjian.ccjava.domain.execution;

import java.util.Map;
import java.util.Objects;

/**
 * 绑定本进程和主机探测身份的后端能力快照，不包含绝对路径、命令输出或 Secret。
 *
 * @param backend 后端
 * @param dimensions 各强制维度真实状态
 * @param hostIdentity 非敏感主机探测摘要
 * @param reasonCode 固定原因码
 * @since 0.13.0
 */
public record PlatformCapabilitySnapshot(
        ExecutionBackendId backend,
        Map<EnforcementDimension, CapabilityStatus> dimensions,
        String hostIdentity,
        String reasonCode) {
    /** 校验五个强制维度与探测身份、原因码均完整。 */
    public PlatformCapabilitySnapshot {
        backend = Objects.requireNonNull(backend);
        dimensions = Map.copyOf(Objects.requireNonNull(dimensions));
        hostIdentity = Objects.requireNonNull(hostIdentity);
        reasonCode = Objects.requireNonNull(reasonCode);
        for (EnforcementDimension dimension : EnforcementDimension.values()) {
            if (!dimensions.containsKey(dimension)) {
                throw new IllegalArgumentException("缺少维度 " + dimension);
            }
        }
    }

    /**
     * 判断五个安全维度是否全部具有真实强制证据。
     *
     * @return 全部维度均为 {@link CapabilityStatus#ENFORCED} 时返回 true
     */
    public boolean fullyEnforced() {
        return dimensions.values().stream()
                .allMatch(value -> value == CapabilityStatus.ENFORCED);
    }
}
