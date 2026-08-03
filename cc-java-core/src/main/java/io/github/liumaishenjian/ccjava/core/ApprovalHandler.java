package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;

/**
 * 当 Permission Gate 返回 ASK 时获取类型化审批响应的端口。
 *
 * <p>Handler 只能返回 Allow Once、范围化 Allow Session 或 Deny；它不能执行 Tool、
 * 改写 Policy Outcome 或访问 Session 私有状态。Core 会校验 Session scope 是否与已展示
 * 调用完全一致。</p>
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
     * @param outcome 触发 ASK 的初始结果与安全 scope
     * @return 类型化审批响应
     */
    ApprovalResponse requestApproval(
            ToolInvocation invocation,
            ToolDefinition definition,
            PermissionOutcome outcome);

}
