package io.github.liumaishenjian.ccjava.core.execution;

import io.github.liumaishenjian.ccjava.domain.execution.ExecutionFallbackDecision;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionRequest;
import io.github.liumaishenjian.ccjava.domain.execution.PlatformCapabilitySnapshot;

/**
 * Sandbox 不可用时对当前 Call ID 收敛一次性 Local 风险决定的端口。
 *
 * <p>非交互、PLAN、Hard Denial 或受管 require-isolation 组合应使用恒拒绝实现。</p>
 *
 * @since 0.13.0
 */
@FunctionalInterface
public interface ExecutionFallbackApprover {
    /**
     * 在进程启动前决定当前调用是否允许回退到 Local。
     *
     * @param request 当前执行请求
     * @param missing 未满足要求的后端能力快照
     * @return 绑定当前 Call ID 的一次性决定
     */
    ExecutionFallbackDecision decide(
            ExecutionRequest request,
            PlatformCapabilitySnapshot missing);

    /**
     * 返回生产装配使用的恒拒绝实现。
     *
     * @return 恒拒绝 fallback approver
     */
    static ExecutionFallbackApprover denyAll() {
        return (request, snapshot) -> ExecutionFallbackDecision.deny(
                request.callId(),
                "FALLBACK_DENIED");
    }
}
