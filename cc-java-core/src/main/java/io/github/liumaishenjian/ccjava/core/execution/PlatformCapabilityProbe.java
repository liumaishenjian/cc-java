package io.github.liumaishenjian.ccjava.core.execution;

import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendId;
import io.github.liumaishenjian.ccjava.domain.execution.PlatformCapabilitySnapshot;

/**
 * 对候选后端执行真实依赖和最小安全自测的端口。
 *
 * <p>探测失败或不确定必须如实返回 UNKNOWN 或 UNAVAILABLE，
 * 不得因 helper 存在而报告强制。</p>
 *
 * @since 0.13.0
 */
public interface PlatformCapabilityProbe {
    /**
     * 探测并绑定指定后端当前平台身份与五维能力。
     *
     * @param backend 候选后端身份
     * @return 隐私安全的能力快照
     */
    PlatformCapabilitySnapshot probe(ExecutionBackendId backend);
}
