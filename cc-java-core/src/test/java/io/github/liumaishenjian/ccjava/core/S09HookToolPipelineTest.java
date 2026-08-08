package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.hook.HookBinding;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.PermissionReason;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultStatus;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.domain.hook.HookDisposition;
import io.github.liumaishenjian.ccjava.domain.hook.HookEventKind;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionResult;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionStatus;
import io.github.liumaishenjian.ccjava.domain.hook.HookFailurePolicy;
import io.github.liumaishenjian.ccjava.domain.hook.HookInvocation;
import io.github.liumaishenjian.ccjava.domain.hook.HookMatcher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证 S09 Hook 已经接到真实 Tool Pipeline，而不是只停留在孤立协调器。
 *
 * @since 0.1.0
 */
class S09HookToolPipelineTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void preToolBlockKeepsPermissionAndSideEffectBehindTheGate() {
        AtomicBoolean executed = new AtomicBoolean();
        AtomicBoolean permissionEvaluated = new AtomicBoolean();
        HookHarness harness = coordinator(
                HookEventKind.PRE_TOOL,
                HookDisposition.BLOCK,
                "policy hook blocked");
        try {
            ToolExecutionPipeline pipeline = pipeline(
                    tool(executed),
                    (invocation, definition) -> {
                        permissionEvaluated.set(true);
                        return allow(definition);
                    },
                    harness.coordinator());

            ToolResult result = pipeline.execute(
                    pipelineSession(pipeline),
                    new RunId("run-1"),
                    1,
                    new ToolCall("call-1", "hooked_tool", JsonObject.empty()));

            assertThat(result.callId()).isEqualTo("call-1");
            assertThat(result.status()).isEqualTo(ToolResultStatus.FAILURE);
            assertThat(result.error()).get().extracting("code")
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.ToolErrorCode.HOOK_BLOCKED);
            assertThat(executed).isFalse();
            assertThat(permissionEvaluated).isFalse();
        } finally {
            harness.executor().shutdownNow();
        }
    }

    @Test
    void postToolIsObservedAfterResultAndCannotRewriteIt() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<HookInvocation> observed = new AtomicReference<>();
        try {
            HookCoordinator hooks = new HookCoordinator(
                    List.of(new HookBinding(
                            "post-observer",
                            HookMatcher.event(HookEventKind.POST_TOOL),
                            (invocation, token) -> {
                                observed.set(invocation);
                                return new HookExecutionResult(
                                        "post-observer",
                                        HookDisposition.BLOCK,
                                        HookExecutionStatus.COMPLETED,
                                        Optional.of("ignored block"),
                                        Optional.of("feedback"));
                            },
                            HookFailurePolicy.FAIL_OPEN,
                            true,
                            0)),
                    executor,
                    Duration.ofSeconds(1));
            ToolExecutionPipeline pipeline = pipeline(
                    tool(new AtomicBoolean()),
                    (invocation, definition) -> allow(definition),
                    hooks);

            ToolResult result = pipeline.execute(
                    pipelineSession(pipeline),
                    new RunId("run-1"),
                    1,
                    new ToolCall("call-2", "hooked_tool", JsonObject.empty()));

            assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
            assertThat(result.content()).isEqualTo("executed");
            assertThat(observed.get()).isNotNull();
            assertThat(observed.get().event()).isEqualTo(HookEventKind.POST_TOOL);
            assertThat(observed.get().data().string("status")).contains("SUCCESS");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void permissionHookCanResolveAskWithoutOpeningApprovalSurface() {
        AtomicBoolean executed = new AtomicBoolean();
        AtomicBoolean approvalRequested = new AtomicBoolean();
        HookHarness harness = coordinator(
                HookEventKind.PERMISSION_REQUEST,
                HookDisposition.ALLOW,
                "trusted hook allowed");
        try {
            ToolExecutionPipeline pipeline = pipeline(
                    tool(executed),
                    (invocation, definition) -> ask(definition),
                    harness.coordinator(),
                    (invocation, definition, outcome) -> {
                        approvalRequested.set(true);
                        return ApprovalResponse.deny();
                    });

            ToolResult result = pipeline.execute(
                    pipelineSession(pipeline),
                    new RunId("run-1"),
                    1,
                    new ToolCall("call-3", "hooked_tool", JsonObject.empty()));

            assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
            assertThat(executed).isTrue();
            assertThat(approvalRequested).isFalse();
        } finally {
            harness.executor().shutdownNow();
        }
    }

    @Test
    void permissionHookDenyStopsAskBeforeToolExecution() {
        AtomicBoolean executed = new AtomicBoolean();
        AtomicBoolean approvalRequested = new AtomicBoolean();
        HookHarness harness = coordinator(
                HookEventKind.PERMISSION_REQUEST,
                HookDisposition.DENY,
                "trusted hook denied");
        try {
            ToolExecutionPipeline pipeline = pipeline(
                    tool(executed),
                    (invocation, definition) -> ask(definition),
                    harness.coordinator(),
                    (invocation, definition, outcome) -> {
                        approvalRequested.set(true);
                        return ApprovalResponse.allowOnce();
                    });

            ToolResult result = pipeline.execute(
                    pipelineSession(pipeline),
                    new RunId("run-1"),
                    1,
                    new ToolCall("call-4", "hooked_tool", JsonObject.empty()));

            assertThat(result.status()).isEqualTo(ToolResultStatus.DENIED);
            assertThat(result.error()).get().extracting("code")
                    .isEqualTo(io.github.liumaishenjian.ccjava.domain.ToolErrorCode.PERMISSION_DENIED);
            assertThat(executed).isFalse();
            assertThat(approvalRequested).isFalse();
        } finally {
            harness.executor().shutdownNow();
        }
    }

    @Test
    void hardDenialNeverInvokesPermissionHook() {
        AtomicBoolean executed = new AtomicBoolean();
        AtomicBoolean hookInvoked = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            HookCoordinator hooks = new HookCoordinator(
                    List.of(new HookBinding(
                            "must-not-run",
                            HookMatcher.event(HookEventKind.PERMISSION_REQUEST),
                            (invocation, token) -> {
                                hookInvoked.set(true);
                                return new HookExecutionResult(
                                        "must-not-run",
                                        HookDisposition.ALLOW,
                                        HookExecutionStatus.COMPLETED,
                                        Optional.empty(),
                                        Optional.empty());
                            },
                            HookFailurePolicy.FAIL_CLOSED,
                            true,
                            0)),
                    executor,
                    Duration.ofSeconds(1));
            ToolExecutionPipeline pipeline = pipeline(
                    tool(executed),
                    (invocation, definition) -> PermissionOutcome.of(
                            PermissionDecision.DENY,
                            PermissionReason.HARD_DENIAL,
                            PermissionSelector.toolWide(definition.name(), definition.source())),
                    hooks);

            ToolResult result = pipeline.execute(
                    pipelineSession(pipeline),
                    new RunId("run-1"),
                    1,
                    new ToolCall("call-5", "hooked_tool", JsonObject.empty()));

            assertThat(result.status()).isEqualTo(ToolResultStatus.DENIED);
            assertThat(executed).isFalse();
            assertThat(hookInvoked).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    private static HookHarness coordinator(
            HookEventKind event,
            HookDisposition disposition,
            String reason) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        return new HookHarness(new HookCoordinator(
                List.of(new HookBinding(
                        "pre-blocker",
                        HookMatcher.event(event),
                        (invocation, token) -> new HookExecutionResult(
                                "pre-blocker",
                                disposition,
                                HookExecutionStatus.COMPLETED,
                                Optional.of(reason),
                                Optional.empty()),
                        HookFailurePolicy.FAIL_OPEN,
                        true,
                        0)),
                executor,
                Duration.ofSeconds(1)), executor);
    }

    private static ToolExecutionPipeline pipeline(
            AgentTool tool,
            PermissionGate permissionGate,
            HookCoordinator hooks) {
        return pipeline(
                tool,
                permissionGate,
                hooks,
                (invocation, definition, outcome) -> ApprovalResponse.allowOnce());
    }

    private static ToolExecutionPipeline pipeline(
            AgentTool tool,
            PermissionGate permissionGate,
            HookCoordinator hooks,
            ApprovalHandler approvalHandler) {
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, AgentEventSink.noop());
        return new ToolExecutionPipeline(
                new ToolRegistry(List.of(tool)),
                permissionGate,
                approvalHandler,
                new InMemorySessionPermissionState(),
                lifecycle,
                SessionJournal.noop(),
                CheckpointCoordinator.noop(),
                hooks);
    }

    private static PermissionOutcome ask(ToolDefinition definition) {
        return PermissionOutcome.of(
                PermissionDecision.ASK,
                PermissionReason.EXPLICIT_ASK,
                PermissionSelector.toolWide(definition.name(), definition.source()));
    }

    private static AgentSession pipelineSession(ToolExecutionPipeline ignoredPipeline) {
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, AgentEventSink.noop());
        return new InMemorySessionStore(new SequentialAgentIdGenerator(), lifecycle)
                .create(SessionSpec.of("s09-test"));
    }

    private static AgentTool tool(AtomicBoolean executed) {
        return new AgentTool() {
            private final ToolDefinition definition = new ToolDefinition(
                    "hooked_tool",
                    "S09 hook integration fixture",
                    "{\"type\":\"object\"}",
                    ToolEffect.READ_WORKSPACE,
                    ToolSource.BUILT_IN,
                    false,
                    Duration.ofSeconds(1),
                    "text/plain",
                    256);

            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolExecutionOutcome execute(ToolInvocation invocation) {
                executed.set(true);
                return ToolExecutionOutcome.success("executed");
            }
        };
    }

    private static PermissionOutcome allow(ToolDefinition definition) {
        return PermissionOutcome.of(
                PermissionDecision.ALLOW,
                PermissionReason.EFFECT_DEFAULT,
                PermissionSelector.toolWide(definition.name(), definition.source()));
    }

    private record HookHarness(HookCoordinator coordinator, ExecutorService executor) {
    }
}
