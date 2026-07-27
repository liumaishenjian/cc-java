package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;

/**
 * 在 Tool 执行前给出确定性的最小权限决策。
 *
 * <p>S01 仅验证端口和 Pipeline 顺序，不实现模式、规则和 Hard Denial。
 * 完整权限语义属于 S04～S05。</p>
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
     * @return ALLOW、ASK 或 DENY
     */
    PermissionDecision evaluate(ToolInvocation invocation, ToolDefinition definition);

}
