package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.hook.HookBinding;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.PermissionReason;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.domain.hook.HookDisposition;
import io.github.liumaishenjian.ccjava.domain.hook.HookEventKind;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionResult;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionStatus;
import io.github.liumaishenjian.ccjava.domain.hook.HookFailurePolicy;
import io.github.liumaishenjian.ccjava.domain.hook.HookMatcher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * 验证 S09 Hook 已进入 Session 与 Agent Runtime 的真实生命周期，而不是只有 Tool 切片。
 *
 * @since 0.1.0
 */
class S09HookRuntimeLifecycleTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void userPromptBlockStopsBeforeJournalAndModelTurn() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            HookCoordinator hooks = new HookCoordinator(
                    List.of(new HookBinding(
                            "prompt-guard",
                            HookMatcher.event(HookEventKind.USER_PROMPT),
                            (invocation, token) -> new HookExecutionResult(
                                    "prompt-guard",
                                    HookDisposition.BLOCK,
                                    HookExecutionStatus.COMPLETED,
                                    Optional.of("prompt rejected"),
                                    Optional.empty()),
                            HookFailurePolicy.FAIL_OPEN,
                            true,
                            0)),
                    executor,
                    Duration.ofSeconds(1));
            ScriptedModelGateway model = ScriptedModelGateway.of(
                    io.github.liumaishenjian.ccjava.domain.ModelTurn.text("must not be requested"));
            RuntimeHarness harness = newHarness(model, hooks);

            AgentRunResult result = harness.runtime().run(
                    harness.session().id(),
                    new AgentRunRequest(new UserMessage("blocked"),
                            new io.github.liumaishenjian.ccjava.domain.AgentLimits(2, 0)));

            assertThat(result.stopReason()).isEqualTo(StopReason.HOOK_BLOCKED);
            assertThat(result.modelTurns()).isZero();
            assertThat(model.requests()).isEmpty();
            assertThat(harness.session().messages()).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void lifecycleHooksObserveSessionRunPromptAndModelBoundariesInOrder() {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<HookEventKind> observed = new CopyOnWriteArrayList<>();
        try {
            List<HookEventKind> events = List.of(
                    HookEventKind.SESSION_START,
                    HookEventKind.USER_PROMPT,
                    HookEventKind.RUN_START,
                    HookEventKind.MODEL_TURN_START,
                    HookEventKind.MODEL_TURN_END,
                    HookEventKind.RUN_END,
                    HookEventKind.SESSION_END);
            List<HookBinding> bindings = new ArrayList<>();
            for (int index = 0; index < events.size(); index++) {
                HookEventKind event = events.get(index);
                String id = "observe-" + event.name().toLowerCase();
                int order = index;
                bindings.add(new HookBinding(
                        id,
                        HookMatcher.event(event),
                        (invocation, token) -> {
                            observed.add(invocation.event());
                            return HookExecutionResult.continued(id);
                        },
                        HookFailurePolicy.FAIL_CLOSED,
                        true,
                        order));
            }
            HookCoordinator hooks = new HookCoordinator(bindings, executor, Duration.ofSeconds(1));
            RuntimeHarness harness = newHarness(
                    ScriptedModelGateway.of(io.github.liumaishenjian.ccjava.domain.ModelTurn.text("done")),
                    hooks);

            AgentRunResult result = harness.runtime().run(
                    harness.session().id(),
                    AgentRunRequest.of("hello"));
            harness.store().close(harness.session().id());

            assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
            assertThat(observed).containsExactlyElementsOf(events);
        } finally {
            executor.shutdownNow();
        }
    }

    private static RuntimeHarness newHarness(
            ScriptedModelGateway model,
            HookCoordinator hooks) {
        SequentialAgentIdGenerator ids = new SequentialAgentIdGenerator();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, AgentEventSink.noop());
        InMemorySessionStore store = new InMemorySessionStore(ids, lifecycle, hooks);
        ToolRegistry registry = new ToolRegistry(List.of());
        PermissionGate permissionGate = (invocation, definition) -> PermissionOutcome.of(
                PermissionDecision.ALLOW,
                PermissionReason.EFFECT_DEFAULT,
                PermissionSelector.toolWide(definition.name(), definition.source()));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                registry,
                permissionGate,
                (invocation, definition, outcome) ->
                        io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce(),
                new InMemorySessionPermissionState(),
                lifecycle,
                SessionJournal.noop(),
                CheckpointCoordinator.noop(),
                hooks);
        AgentRuntime runtime = new AgentRuntime(
                store,
                ids,
                model,
                new DefaultContextAssembler(),
                registry,
                pipeline,
                lifecycle,
                SessionJournal.noop(),
                ContextPreparationService.noop(),
                MemoryContextService.noop(),
                io.github.liumaishenjian.ccjava.core.instructions.InstructionContextService.noop(),
                hooks);
        AgentSession session = store.create(SessionSpec.of("S09 lifecycle test"));
        return new RuntimeHarness(runtime, store, session);
    }

    private record RuntimeHarness(
            AgentRuntime runtime,
            InMemorySessionStore store,
            AgentSession session) {
    }
}
