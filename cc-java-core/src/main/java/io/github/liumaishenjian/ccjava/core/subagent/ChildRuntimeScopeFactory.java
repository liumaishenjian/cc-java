package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.subagent.AgentDefinitionSnapshot;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskRequest;
import io.github.liumaishenjian.ccjava.domain.subagent.DelegationId;
import java.util.Optional;

/**
 * 在执行边界重新装配独立子 Runtime scope 的 Application Port。
 *
 * @since 0.12.0
 */
@FunctionalInterface
public interface ChildRuntimeScopeFactory {
    /**
     * 根据收窄定义创建独立 Session/Context/Tool/Permission scope。
     *
     * @param definition 已冻结并完成收窄的定义
     * @param request 当前委托请求
     * @param cancellationToken 父级传播的取消身份
     * @return 由调用方关闭的独立 scope
     */
    ChildRuntimeScope create(
            AgentDefinitionSnapshot definition,
            ChildTaskRequest request,
            CancellationToken cancellationToken);

    /**
     * 对任务绑定的 retained worktree 执行显式 keep。
     *
     * @param id 任务 delegation identity
     * @return 固定处置状态；无 worktree 时为空
     */
    default Optional<String> keepWorktree(DelegationId id) {
        return Optional.empty();
    }

    /**
     * 对任务绑定的 retained worktree 执行显式 clean remove；不确定时必须保留。
     *
     * @param id 任务 delegation identity
     * @return 固定处置状态；无 worktree 时为空
     */
    default Optional<String> removeWorktree(DelegationId id) {
        return Optional.empty();
    }
}
