package io.github.liumaishenjian.ccjava.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.subagent.*;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** S12 真实 delegate Tool → child model → child Tool → 独立 Pipeline 的生产协议 E2E。 */
class DelegateAgentProductionE2ETest {
    private static final ChildBudget BUDGET = new ChildBudget(2, 1, 2048, 256, Duration.ofSeconds(2));
    private static final AgentDefinitionSnapshot DEFINITION = new AgentDefinitionSnapshot(
            new AgentDefinitionId("e2e"), "child", "isolated child", Set.of("child_read"),
            PermissionMode.PLAN, "fake", BUDGET, false, "a".repeat(64), "project");

    @Test
    void thirdLevelHostProvenanceIsRejectedWithoutBudgetTaskOrSessionLeak() {
        AtomicInteger scopeCreations = new AtomicInteger();
        ChildBudget capacity = new ChildBudget(4, 2, 4096, 512, Duration.ofSeconds(5));
        ChildBudgetLedger ledger = new ChildBudgetLedger(capacity);
        ChildRuntimeScopeFactory factory = (definition, request, cancellation) -> {
            scopeCreations.incrementAndGet();
            throw new AssertionError("超过深度的请求不得创建 child Session");
        };
        try (AgentSupervisor supervisor = new AgentSupervisor(catalog(), factory, ledger)) {
            // depth=3 代表 root(depth=1) -> child(depth=2) -> grandchild；该 provenance 只由 Host 构造。
            DelegateAgentTool thirdLevel = new DelegateAgentTool(supervisor, 3);
            JsonObject forged = new JsonObject(Map.of(
                    "definition", "e2e", "prompt", "nested", "tools", List.of(), "depth", 1));
            assertThat(thirdLevel.validate(forged).valid()).isFalse();

            JsonObject args = new JsonObject(Map.of(
                    "definition", "e2e", "prompt", "nested", "tools", List.of(),
                    "maxModelTurns", 2, "maxToolCalls", 1, "maxInputTokens", 2048L,
                    "maxOutputCharacters", 256, "timeoutSeconds", 2));
            assertThatThrownBy(() -> thirdLevel.execute(new ToolInvocation(
                    new SessionId("parent"), new RunId("run"), 1,
                    new ToolCall("delegate-third", "delegate_agent", args), CancellationToken.none())))
                    .isInstanceOf(java.util.concurrent.RejectedExecutionException.class)
                    .hasMessageContaining("深度");
            assertThat(scopeCreations).hasValue(0);
            assertThat(ledger.remaining()).isEqualTo(capacity);
            assertThat(supervisor.find(new ChildTaskId("task-1"))).isEmpty();
        }
    }

    @Test
    void delegateRunsChildToolOnlyThroughIndependentPipelineAndReturnsBoundedReport() {
        AtomicInteger childExecutions = new AtomicInteger();
        AtomicInteger childAfterTool = new AtomicInteger();
        AtomicInteger parentAfterTool = new AtomicInteger();
        AtomicInteger childTurns = new AtomicInteger();
        ChildRuntimeScopeFactory factory = (definition, request, cancellation) -> childScope(
                childExecutions, childAfterTool, childTurns);
        try (AgentSupervisor supervisor = new AgentSupervisor(catalog(), factory,
                new ChildBudgetLedger(new ChildBudget(4, 2, 4096, 512, Duration.ofSeconds(5))))) {
            DelegateAgentTool delegate = new DelegateAgentTool(supervisor);
            LifecycleDispatcher parentLifecycle = new LifecycleDispatcher(Clock.systemUTC(), event -> {
                if (event.event() instanceof LifecycleEvent.AfterTool) parentAfterTool.incrementAndGet();
            });
            ToolRegistry parentRegistry = new ToolRegistry(List.of(delegate));
            ToolExecutionPipeline parentPipeline = new ToolExecutionPipeline(parentRegistry,
                    (invocation, definition) -> PermissionOutcome.of(PermissionDecision.ALLOW,
                            PermissionReason.EFFECT_DEFAULT,
                            PermissionSelector.toolWide(definition.name(), definition.source())),
                    (invocation, definition, outcome) -> ApprovalResponse.allowOnce(), parentLifecycle);
            AgentSession parent = new InMemorySessionStore(ids("parent"), parentLifecycle).create(SessionSpec.of("parent"));
            JsonObject args = new JsonObject(Map.of(
                    "definition", "e2e", "prompt", "read fixture", "tools", List.of("child_read"),
                    "maxModelTurns", 2, "maxToolCalls", 1, "maxInputTokens", 2048L,
                    "maxOutputCharacters", 256, "timeoutSeconds", 2));
            ToolResult result = parentPipeline.execute(parent, new RunId("parent-run"), 1,
                    new ToolCall("delegate-call", "delegate_agent", args), CancellationToken.none());

            assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
            assertThat(result.content()).contains("status=succeeded", "modelTurns=2", "toolCalls=1", "verified=true")
                    .doesNotContain("child secret payload", "isolated child");
            assertThat(childTurns).hasValue(2);
            assertThat(childExecutions).hasValue(1);
            assertThat(childAfterTool).hasValue(1);
            assertThat(parentAfterTool).hasValue(1);
            assertThat(childExecutions.get() - childAfterTool.get()).isZero();
        }
    }

    private static ChildRuntimeScope childScope(AtomicInteger executions, AtomicInteger afterTool, AtomicInteger turns) {
        ModelGateway gateway = request -> {
            turns.incrementAndGet();
            return request.turnNumber() == 1
                    ? ModelTurn.tools(List.of(new ToolCall("child-call", "child_read", JsonObject.empty())))
                    : ModelTurn.text("child secret payload");
        };
        AgentTool childTool = new AgentTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("child_read", "child read", "{}", ToolEffect.READ_WORKSPACE,
                        ToolSource.BUILT_IN, false, Duration.ofSeconds(1), "text/plain", 128);
            }
            @Override public ToolValidationResult validate(JsonObject arguments) { return ToolValidationResult.validResult(); }
            @Override public ToolExecutionOutcome execute(ToolInvocation invocation) {
                executions.incrementAndGet();
                return ToolExecutionOutcome.success("child secret payload");
            }
        };
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(Clock.systemUTC(), event -> {
            if (event.event() instanceof LifecycleEvent.AfterTool) afterTool.incrementAndGet();
        });
        AgentIdGenerator ids = ids("child");
        InMemorySessionStore sessions = new InMemorySessionStore(ids, lifecycle);
        ToolRegistry registry = new ToolRegistry(List.of(childTool));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry,
                (invocation, definition) -> PermissionOutcome.of(PermissionDecision.ALLOW,
                        PermissionReason.EFFECT_DEFAULT, PermissionSelector.toolWide(definition.name(), definition.source())),
                (invocation, definition, outcome) -> ApprovalResponse.deny(), lifecycle);
        AgentRuntime runtime = new AgentRuntime(sessions, ids, gateway, new DefaultContextAssembler(), registry, pipeline, lifecycle);
        AgentSession child = sessions.create(SessionSpec.of("child"));
        return new ChildRuntimeScope(runtime, child.id(), () -> sessions.close(child.id()));
    }

    private static AgentDefinitionCatalog catalog() {
        return new AgentDefinitionCatalog() {
            @Override public Optional<AgentDefinitionSnapshot> find(AgentDefinitionId id) {
                return id.equals(DEFINITION.id()) ? Optional.of(DEFINITION) : Optional.empty();
            }
            @Override public List<AgentDefinitionSnapshot> snapshots() { return List.of(DEFINITION); }
        };
    }

    private static AgentIdGenerator ids(String prefix) {
        return new AgentIdGenerator() {
            private final AtomicInteger value = new AtomicInteger();
            @Override public SessionId newSessionId() { return new SessionId(prefix + "-session-" + value.incrementAndGet()); }
            @Override public RunId newRunId() { return new RunId(prefix + "-run-" + value.incrementAndGet()); }
        };
    }
}
