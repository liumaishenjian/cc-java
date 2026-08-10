package io.github.liumaishenjian.ccjava.core.execution;

import io.github.liumaishenjian.ccjava.domain.execution.ExecutionPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.PlatformCapabilitySnapshot;

/**
 * PERM-05 的确定性 Auto skeleton：只有所有要求维度真实强制且策略允许时免询问。
 *
 * <p>它不使用模型分类器，也不覆盖 Hard Denial、PLAN 或 Permission rules。
 * 当前类型尚未接入生产装配，PERM-05 保持 L0。</p>
 *
 * @since 0.13.0
 */
public final class DeterministicAutoExecutionGate {
    /**
     * 判断策略和平台证据是否满足跳过交互审批的必要条件。
     *
     * @param policy 当前有效执行策略
     * @param capability 已绑定身份的能力快照
     * @return 要求隔离且五维全部强制时返回 true
     */
    public boolean maySkipInteractiveApproval(
            ExecutionPolicy policy,
            PlatformCapabilitySnapshot capability) {
        return policy.requireIsolation() && capability.fullyEnforced();
    }
}
