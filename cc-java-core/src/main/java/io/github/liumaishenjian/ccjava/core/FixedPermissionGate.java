package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import java.util.Objects;

/**
 * 实现 S04 不可配置的最小权限决策表。
 *
 * <p>该类型只根据可信的 Tool Effect 和当前模式返回 Allow、Ask 或 Deny，不读取
 * Tool 参数，也不执行审批。参数级安全校验仍由 Tool Adapter 负责，交互审批由
 * {@link ApprovalHandler} 负责；完整规则系统属于 S05。</p>
 *
 * @since 0.1.0
 */
public final class FixedPermissionGate implements PermissionGate {

    private final PermissionMode mode;

    /**
     * 创建固定模式的权限决策器。
     *
     * @param mode 当前固定权限模式
     */
    public FixedPermissionGate(PermissionMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode 不能为空");
    }

    /**
     * 根据 S04 固定决策表评估 Tool 的最高副作用。
     *
     * @param invocation 已通过参数校验的调用上下文
     * @param definition Tool Definition
     * @return Read 为 Allow；DEFAULT 的 Write/Process 为 Ask；其余为 Deny
     */
    @Override
    public PermissionDecision evaluate(
            ToolInvocation invocation,
            ToolDefinition definition) {
        Objects.requireNonNull(invocation, "invocation 不能为空");
        ToolEffect effect = Objects.requireNonNull(
                definition, "definition 不能为空").effect();
        if (effect == ToolEffect.READ_WORKSPACE) {
            return PermissionDecision.ALLOW;
        }
        if (mode == PermissionMode.DEFAULT
                && (effect == ToolEffect.WRITE_WORKSPACE
                || effect == ToolEffect.EXECUTE_PROCESS)) {
            return PermissionDecision.ASK;
        }
        return PermissionDecision.DENY;
    }
}
