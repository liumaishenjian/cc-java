package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.hook.HookBinding;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.core.plugin.InMemoryPluginRegistry;
import io.github.liumaishenjian.ccjava.core.plugin.PluginRunCoordinator;
import io.github.liumaishenjian.ccjava.core.plugin.PluginRunHooks;
import io.github.liumaishenjian.ccjava.core.skill.SkillRunCoordinator;
import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionResult;
import io.github.liumaishenjian.ccjava.domain.hook.HookEventKind;
import io.github.liumaishenjian.ccjava.domain.hook.HookFailurePolicy;
import io.github.liumaishenjian.ccjava.domain.hook.HookMatcher;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginComponentDescriptor;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginComponentKind;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginFingerprint;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginId;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginManifest;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证 Plugin Hook 只在当前 Run 激活并在所有终态前解绑、后释放 snapshot lease。 */
class S11PluginRunHookLifecycleTest {
    @Test
    void trustedHookRunsOnlyInsideCapturedRunAndSuccessReleasesEverything() {
        PluginSnapshot snapshot = snapshot();
        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        registry.activate(snapshot);
        var plugins = new PluginRunCoordinator(registry);
        var calls = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var hooks = new HookCoordinator(List.of(), executor, Duration.ofSeconds(1));
            PluginRunHooks templates = (runId, fingerprints) -> fingerprints.isEmpty() ? List.of() : List.of(
                    new HookBinding("plugin-run", HookMatcher.event(HookEventKind.RUN_START),
                            (invocation, cancellation) -> {
                                calls.incrementAndGet();
                                return HookExecutionResult.continued("plugin-run");
                            }, HookFailurePolicy.FAIL_OPEN, true, 100));
            var ids = new SequentialAgentIdGenerator();
            var lifecycle = new LifecycleDispatcher(Clock.systemUTC(), AgentEventSink.noop());
            var sessions = new InMemorySessionStore(ids, lifecycle);
            var registryTools = new ToolRegistry(List.of());
            var pipeline = new ToolExecutionPipeline(registryTools,
                    new FixedPermissionGate(io.github.liumaishenjian.ccjava.domain.PermissionMode.DEFAULT),
                    (invocation, definition, outcome) -> io.github.liumaishenjian.ccjava.domain.ApprovalResponse.deny(),
                    lifecycle);
            var runtime = new AgentRuntime(sessions, ids, request -> io.github.liumaishenjian.ccjava.domain.ModelTurn.text("done"),
                    new DefaultContextAssembler(), registryTools, pipeline, lifecycle, SessionJournal.noop(),
                    ContextPreparationService.noop(), MemoryContextService.noop(),
                    io.github.liumaishenjian.ccjava.core.instructions.InstructionContextService.noop(), hooks,
                    SkillRunCoordinator.disabled(), plugins, templates);
            var session = sessions.create(SessionSpec.of("system"));

            runtime.run(session.id(), new AgentRunRequest(new UserMessage("first"), AgentLimits.DEFAULT));
            assertThat(calls).hasValue(1);
            assertThat(registry.leaseCount(snapshot.manifest().id())).isZero();
            assertThat(hooks.runBindingCount(new io.github.liumaishenjian.ccjava.domain.RunId("run-1"))).isZero();
            hooks.evaluate(new io.github.liumaishenjian.ccjava.domain.hook.HookInvocation(
                    HookEventKind.RUN_START, session.id(), java.util.Optional.of(
                            new io.github.liumaishenjian.ccjava.domain.RunId("resumed-no-run")),
                    "resume", io.github.liumaishenjian.ccjava.domain.JsonObject.empty()), CancellationToken.none());
            assertThat(calls).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelledRunAlsoUnbindsHookAndReleasesLease() throws Exception {
        PluginSnapshot snapshot = snapshot();
        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        registry.activate(snapshot);
        var plugins = new PluginRunCoordinator(registry);
        var calls = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        var modelStarted = new java.util.concurrent.CountDownLatch(1);
        try {
            var hooks = new HookCoordinator(List.of(), executor, Duration.ofSeconds(1));
            PluginRunHooks templates = (runId, fingerprints) -> List.of(new HookBinding(
                    "plugin-run", HookMatcher.event(HookEventKind.RUN_START),
                    (invocation, cancellation) -> {
                        calls.incrementAndGet();
                        return HookExecutionResult.continued("plugin-run");
                    }, HookFailurePolicy.FAIL_OPEN, true, 100));
            var ids = new SequentialAgentIdGenerator();
            var lifecycle = new LifecycleDispatcher(Clock.systemUTC(), AgentEventSink.noop());
            var sessions = new InMemorySessionStore(ids, lifecycle);
            var registryTools = new ToolRegistry(List.of());
            var pipeline = new ToolExecutionPipeline(registryTools,
                    new FixedPermissionGate(io.github.liumaishenjian.ccjava.domain.PermissionMode.DEFAULT),
                    (invocation, definition, outcome) -> io.github.liumaishenjian.ccjava.domain.ApprovalResponse.deny(),
                    lifecycle);
            StreamingModelGateway model = (request, observer, cancellation) -> {
                modelStarted.countDown();
                while (!cancellation.isCancellationRequested()) Thread.onSpinWait();
                throw new ModelGatewayException("cancelled");
            };
            var runtime = new AgentRuntime(sessions, ids, model, new DefaultContextAssembler(), registryTools,
                    pipeline, lifecycle, SessionJournal.noop(), ContextPreparationService.noop(),
                    MemoryContextService.noop(),
                    io.github.liumaishenjian.ccjava.core.instructions.InstructionContextService.noop(), hooks,
                    SkillRunCoordinator.disabled(), plugins, templates);
            var session = sessions.create(SessionSpec.of("system"));
            var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> runtime.run(session.id(),
                    new AgentRunRequest(new UserMessage("cancel"), AgentLimits.DEFAULT)));
            assertThat(modelStarted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.cancel(session.id(), new io.github.liumaishenjian.ccjava.domain.RunId("run-1"))).isTrue();
            assertThat(future.get(2, java.util.concurrent.TimeUnit.SECONDS).stopReason())
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.StopReason.USER_CANCELLED);
            assertThat(calls).hasValue(1);
            assertThat(registry.leaseCount(snapshot.manifest().id())).isZero();
            assertThat(hooks.runBindingCount(new io.github.liumaishenjian.ccjava.domain.RunId("run-1"))).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void hookTemplateFailureRollsBackCapturedPluginLeaseBeforeModelCall() {
        PluginSnapshot snapshot = snapshot();
        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        registry.activate(snapshot);
        var plugins = new PluginRunCoordinator(registry);
        var modelCalls = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var hooks = new HookCoordinator(List.of(), executor, Duration.ofSeconds(1));
            var ids = new SequentialAgentIdGenerator();
            var lifecycle = new LifecycleDispatcher(Clock.systemUTC(), AgentEventSink.noop());
            var sessions = new InMemorySessionStore(ids, lifecycle);
            var registryTools = new ToolRegistry(List.of());
            var pipeline = new ToolExecutionPipeline(registryTools,
                    new FixedPermissionGate(io.github.liumaishenjian.ccjava.domain.PermissionMode.DEFAULT),
                    (invocation, definition, outcome) -> io.github.liumaishenjian.ccjava.domain.ApprovalResponse.deny(),
                    lifecycle);
            var runtime = new AgentRuntime(sessions, ids, request -> {
                modelCalls.incrementAndGet();
                return io.github.liumaishenjian.ccjava.domain.ModelTurn.text("unexpected");
            }, new DefaultContextAssembler(), registryTools, pipeline, lifecycle, SessionJournal.noop(),
                    ContextPreparationService.noop(), MemoryContextService.noop(),
                    io.github.liumaishenjian.ccjava.core.instructions.InstructionContextService.noop(), hooks,
                    SkillRunCoordinator.disabled(), plugins,
                    (runId, fingerprints) -> { throw new IllegalStateException("template invalid"); });
            var session = sessions.create(SessionSpec.of("system"));

            assertThat(runtime.run(session.id(), new AgentRunRequest(new UserMessage("fail"), AgentLimits.DEFAULT))
                    .stopReason()).isEqualTo(io.github.liumaishenjian.ccjava.domain.StopReason.INTERNAL_ERROR);
            assertThat(modelCalls).hasValue(0);
            assertThat(registry.leaseCount(snapshot.manifest().id())).isZero();
            assertThat(hooks.runBindingCount(new io.github.liumaishenjian.ccjava.domain.RunId("run-1"))).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private static PluginSnapshot snapshot() {
        PluginId id = new PluginId("alpha");
        var manifest = new PluginManifest(1, id, "1", null, null, List.of(
                new PluginComponentDescriptor(PluginComponentKind.HOOKS, "run", "hooks/run.json",
                        null, List.of(), null)));
        return new PluginSnapshot(manifest, new PluginFingerprint(id, "1", "a".repeat(64), "b".repeat(64)),
                "a".repeat(32));
    }
}
