package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;

/**
 * 在所有可配置规则、模式和人工批准之前执行的可信 Hard Denial。
 *
 * <p>该策略属于应用层安全边界而非 OS Sandbox。返回拒绝后任何 Startup/Session
 * Allow 都不能覆盖；返回允许后 Tool Adapter 仍必须执行自己的 WorkspaceGuard、
 * 参数、TOCTOU 与进程约束。</p>
 *
 * @since 0.5.0
 */
@FunctionalInterface
public interface HardDenialPolicy {

    /**
     * 判断调用是否不可被批准。
     *
     * @param invocation 已通过 Tool 参数校验的调用
     * @param definition 可信 Tool Definition
     * @param selector 可信提取器产生的范围
     * @return 必须拒绝时为 {@code true}
     */
    boolean denies(
            ToolInvocation invocation,
            ToolDefinition definition,
            PermissionSelector selector);
}
