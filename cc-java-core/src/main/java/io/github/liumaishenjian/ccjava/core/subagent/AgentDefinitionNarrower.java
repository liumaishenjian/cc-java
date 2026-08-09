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
    AgentDefinitionSnapshot narrow(AgentDefinitionSnapshot original, ChildTaskRequest request);
    static AgentDefinitionNarrower identity() { return (original, ignored) -> original; }
}
