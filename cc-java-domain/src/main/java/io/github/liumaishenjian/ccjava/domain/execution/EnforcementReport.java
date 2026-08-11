package io.github.liumaishenjian.ccjava.domain.execution;

import java.util.Map;
import java.util.Objects;

/**
 * 一次实际执行的隐私安全强制报告；不得包含 argv、环境值或路径。
 *
 * @param backend 实际使用后端
 * @param dimensions 实际维度
 * @param fallback 是否显式使用 Local fallback
 * @param reasonCode 固定原因码
 * @since 0.13.0
 */
public record EnforcementReport(
        ExecutionBackendId backend,
        Map<EnforcementDimension, CapabilityStatus> dimensions,
        boolean fallback,
        String reasonCode) {
    /** 校验五维状态与后端 identity 后冻结报告。 */
    public EnforcementReport {
        backend = Objects.requireNonNull(backend);
        dimensions = Map.copyOf(Objects.requireNonNull(dimensions));
        reasonCode = Objects.requireNonNull(reasonCode);
    }
}
