package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;

/**
 * 当 Permission Gate 返回 ASK 时获取最终审批决定的端口。
 *
 * <p>Handler 只能返回 ALLOW 或 DENY。S01 使用确定性 Fake，真正终端审批
 * 在 S04 接入，Session 授权范围在 S05 完成。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface ApprovalHandler {

    /**
     * 获取一次调用的最终审批决定。
     *
     * @param invocation 调用上下文
     * @param definition Tool Definition
     * @return ALLOW 或 DENY
     */
    PermissionDecision requestApproval(
            ToolInvocation invocation,
            ToolDefinition definition);

}
