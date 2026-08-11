package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.domain.subagent.AgentDefinitionSnapshot;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskRequest;

/**
 * HOOK-11 L1 宿主预注册的纯收窄决策 seam。
 *
 * <p>实现不得新增 Tool、提升 Permission/预算、改 Workspace 或改变父取消所有权。</p>
 * @since 0.12.0
 */
@FunctionalInterface
public interface AgentDefinitionNarrower {
    /**
     * 在宿主可信边界内进一步收窄定义。
     *
     * @param original 已冻结的原始定义
     * @param request 当前 child 委托请求
     * @return 不得扩大任何 ceiling 的定义
     */
    AgentDefinitionSnapshot narrow(AgentDefinitionSnapshot original, ChildTaskRequest request);

    /**
     * 创建不改变冻结定义的默认实现。
     *
     * @return 保持原定义的收窄器
     */
    static AgentDefinitionNarrower identity() {
        return (original, ignored) -> original;
    }
}
