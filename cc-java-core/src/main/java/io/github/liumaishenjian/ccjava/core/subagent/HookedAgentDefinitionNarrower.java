package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.hook.HookEventKind;
import io.github.liumaishenjian.ccjava.domain.hook.HookInvocation;
import io.github.liumaishenjian.ccjava.domain.subagent.AgentDefinitionSnapshot;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskRequest;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

/**
 * 把 HOOK-11 的宿主可信 Agent definition 决策点接到现有 Hook Coordinator。
 *
 * <p>S12 不解析 Hook 返回的任意 definition 或 Prompt；Hook 只能阻断，实际 definition 仍原样交给
 * Supervisor 的纯收窄验证。因此扩展文本不能新增 Tool、提升 Permission/预算或替换模型。</p>
 *
 * @since 0.12.0
 */
public final class HookedAgentDefinitionNarrower implements AgentDefinitionNarrower {
    private final HookCoordinator hooks;
    private final SessionId parentSessionId;

    public HookedAgentDefinitionNarrower(HookCoordinator hooks, SessionId parentSessionId) {
        this.hooks = Objects.requireNonNull(hooks);
        this.parentSessionId = Objects.requireNonNull(parentSessionId);
    }

    @Override
    public AgentDefinitionSnapshot narrow(AgentDefinitionSnapshot original, ChildTaskRequest request) {
        var result = hooks.evaluate(new HookInvocation(HookEventKind.AGENT_DEFINITION, parentSessionId,
                Optional.empty(), original.id().value(), new JsonObject(Map.of(
                        "definitionId", original.id().value(),
                        "source", original.sourceKind(),
                        "toolCount", original.visibleTools().size(),
                        "depth", request.depth()))), CancellationToken.none());
        if (result.blocking()) throw new RejectedExecutionException("Agent definition Hook 阻断");
        return original;
    }
}
