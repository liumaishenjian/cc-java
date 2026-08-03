package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;

/**
 * 从已通过 Tool 参数校验的调用中生成可信 Permission Selector。
 *
 * <p>Resolver 必须按 Tool 语义提取和规范化范围；模型参数中的 {@code rule}、
 * {@code source}、{@code effect} 等伪字段都不能直接参与决策。无法安全提取时返回
 * Tool-wide selector，使规则不能误命中具体 Session Grant。</p>
 *
 * @since 0.5.0
 */
@FunctionalInterface
public interface PermissionSelectorResolver {

    /**
     * 解析调用范围。
     *
     * @param invocation 已通过 Tool 参数校验的调用
     * @param definition 可信 Tool Definition
     * @return 非空规范化 selector
     */
    PermissionSelector resolve(ToolInvocation invocation, ToolDefinition definition);
}
