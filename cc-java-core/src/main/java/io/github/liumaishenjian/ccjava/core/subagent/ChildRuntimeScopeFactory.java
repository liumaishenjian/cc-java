package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.subagent.AgentDefinitionSnapshot;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskRequest;

/**
 * 在执行边界重新装配独立子 Runtime scope 的 Application Port。
 * @since 0.12.0
 */
@FunctionalInterface
public interface ChildRuntimeScopeFactory {
    ChildRuntimeScope create(AgentDefinitionSnapshot definition, ChildTaskRequest request,
                             CancellationToken cancellationToken);

    /** 对任务绑定的 retained worktree 执行显式 keep；实现必须校验任务 identity。 */
    default java.util.Optional<String> keepWorktree(io.github.liumaishenjian.ccjava.domain.subagent.DelegationId id) {
        return java.util.Optional.empty();
    }

    /** 对任务绑定的 retained worktree 执行显式 clean remove；不确定时必须保留。 */
    default java.util.Optional<String> removeWorktree(io.github.liumaishenjian.ccjava.domain.subagent.DelegationId id) {
        return java.util.Optional.empty();
    }
}
