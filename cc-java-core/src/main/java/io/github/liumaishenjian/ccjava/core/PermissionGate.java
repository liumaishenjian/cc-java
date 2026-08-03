package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;

/**
 * 在 Tool 执行前给出确定性的类型化权限结果。
 *
 * <p>S05 实现必须在此端口内固定 Hard Denial、规则、模式和 Session 状态优先级；
 * Surface 只能对 ASK 收敛审批，不能覆盖早先的拒绝或直接执行 Tool。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface PermissionGate {

    /**
     * 评估一次已经通过参数校验的 Tool 调用。
     *
     * @param invocation 调用上下文
     * @param definition Tool Definition
     * @return 携带决定、固定原因、来源和 selector 的 outcome
     */
    PermissionOutcome evaluate(ToolInvocation invocation, ToolDefinition definition);

}
