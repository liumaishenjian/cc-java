package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.hook.HookEventKind;
import io.github.liumaishenjian.ccjava.domain.hook.HookInvocation;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskReport;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskRequest;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 在子 Scope 创建前与 durable terminal 后投影 HOOK-08 生命周期。
 *
 * <p>Start 可阻断并返回有界非可信 Context；Stop 仅观察固定摘要，不得改写终态或触发重放。</p>
 *
 * @since 0.12.0
 */
public final class HookedChildTaskLifecycle implements ChildTaskLifecycle {
    private final HookCoordinator hooks;
    private final SessionId parentSessionId;

    public HookedChildTaskLifecycle(HookCoordinator hooks, SessionId parentSessionId) {
        this.hooks = Objects.requireNonNull(hooks);
        this.parentSessionId = Objects.requireNonNull(parentSessionId);
    }

    @Override
    public Optional<String> beforeStart(ChildTaskRequest request, CancellationToken cancellationToken) {
        var result = hooks.evaluate(new HookInvocation(HookEventKind.SUB_AGENT_START, parentSessionId,
                Optional.empty(), request.definitionId().value(), new JsonObject(Map.of(
                        "definitionId", request.definitionId().value(), "depth", request.depth(),
                        "background", request.background(), "worktree", request.worktree()))), cancellationToken);
        if (result.blocking()) throw new ChildTaskStartBlockedException();
        return result.additionalContext();
    }

    @Override
    public Optional<String> afterTerminal(ChildTaskReport report) {
        return hooks.evaluate(new HookInvocation(HookEventKind.SUB_AGENT_STOP, parentSessionId, Optional.empty(),
                report.definitionId().value(), new JsonObject(Map.of(
                        "taskId", report.taskId().value(), "status", report.status().name(),
                        "failure", report.failureCode().name(), "modelTurns", report.modelTurns(),
                        "toolCalls", report.toolCalls()))), CancellationToken.none()).additionalContext();
    }
}
