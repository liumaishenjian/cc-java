package io.github.liumaishenjian.ccjava.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.subagent.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 使用六个公开 seed、每策略五次真实驱动 Supervisor、AgentRuntime、Pipeline 与文件 Tool 的 S12 Eval。
 *
 * <p>所有指标均来自实际 child run：模型请求用于估算输入 Token，Pipeline lifecycle 用于核对 Tool 次数，
 * 临时文件 Tool 用于验证事实/约束与冲突，阻塞模型用于实测取消延迟；不接受由 seed 公式生成的结果。</p>
 */
class S12MultiAgentEvalTest {
    private static final List<Seed> SEEDS = List.of(
            new Seed("calculator-read", "divide-by-zero-preserved", 35),
            new Seed("protocol-map", "call-id-order-preserved", 40),
            new Seed("docs-crosscheck", "source-boundary-preserved", 45),
            new Seed("tests-inventory", "offline-fake-preserved", 50),
            new Seed("two-file-plan", "no-write-preserved", 55),
            new Seed("conflict-detect", "shared-read-only-preserved", 60));
    private static final ChildBudget CHILD_BUDGET = new ChildBudget(2, 1, 2048, 256, Duration.ofSeconds(3));
    private static final AgentDefinitionSnapshot DEFINITION = new AgentDefinitionSnapshot(
            new AgentDefinitionId("eval"), "read-only eval", "preserve fixture constraint", Set.of("read_fixture"),
            PermissionMode.PLAN, "fake", CHILD_BUDGET, false, "e".repeat(64), "user");

    @TempDir Path temp;

    @Test
    void measuredEvalMeetsFrozenQualityCostTimeAndSafetyThresholds() throws Exception {
        List<Metric> single = replay(false);
        List<Metric> multi = replay(true);
        assertThat(single).hasSize(30);
        assertThat(multi).hasSize(30);
        assertThat(single).allSatisfy(this::assertSafeComplete);
        assertThat(multi).allSatisfy(this::assertSafeComplete);

        double completionSingle = completion(single);
        double completionMulti = completion(multi);
        double wallImprovement = 1.0 - medianWall(multi) / medianWall(single);
        double tokenChange = medianTokens(multi) / medianTokens(single) - 1.0;
        assertThat(completionMulti).isGreaterThanOrEqualTo(completionSingle);
        assertThat(wallImprovement).isGreaterThanOrEqualTo(0.20);
        assertThat(tokenChange).isLessThanOrEqualTo(0.25);
    }

    private void assertSafeComplete(Metric metric) {
        assertThat(metric.completed()).isTrue();
        assertThat(metric.constraintsPreserved()).isEqualTo(2);
        assertThat(metric.modelTurns()).isEqualTo(4);
        assertThat(metric.toolCalls()).isEqualTo(2);
        assertThat(metric.estimatedInputTokens()).isPositive();
        assertThat(metric.wallNanos()).isPositive();
        assertThat(metric.cancelLatencyNanos()).isBetween(1L, Duration.ofSeconds(1).toNanos());
        assertThat(metric.fileConflicts()).isZero();
        assertThat(metric.unapprovedSideEffects()).isZero();
        assertThat(metric.pipelineBypasses()).isZero();
    }

    private List<Metric> replay(boolean concurrent) throws Exception {
        List<Metric> values = new ArrayList<>();
        for (Seed seed : SEEDS) {
            Path fixture = temp.resolve(seed.id() + ".txt");
            Files.writeString(fixture, seed.expected());
            for (int replay = 0; replay < 5; replay++) values.add(runStrategy(seed, fixture, concurrent, replay));
        }
        return values;
    }

    private Metric runStrategy(Seed seed, Path fixture, boolean concurrent, int replay) throws Exception {
        AtomicInteger constraints = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger lifecycleAfterTool = new AtomicInteger();
        AtomicInteger unapproved = new AtomicInteger();
        AtomicLong inputCharacters = new AtomicLong();
        CountDownLatch cancelEntered = new CountDownLatch(1);
        ChildRuntimeScopeFactory factory = (definition, request, cancellation) -> scope(
                seed, fixture, request, constraints, executions, lifecycleAfterTool, unapproved,
                inputCharacters, cancelEntered);
        List<ChildTaskReport> reports = new ArrayList<>();
        long started = System.nanoTime();
        try (AgentSupervisor supervisor = new AgentSupervisor(catalog(), factory,
                new ChildBudgetLedger(new ChildBudget(6, 3, 8192, 1024, Duration.ofSeconds(10))),
                AgentDefinitionNarrower.identity(), ChildTaskJournal.noop(), ChildTaskObserver.noop(),
                ChildTaskLifecycle.noop(), Clock.systemUTC(), concurrent ? 2 : 1, 2, 2)) {
            if (concurrent) {
                ChildTaskHandle first = supervisor.submit(request(seed.id() + "-a-" + replay), CancellationToken.none());
                ChildTaskHandle second = supervisor.submit(request(seed.id() + "-b-" + replay), CancellationToken.none());
                reports.add(first.await(Duration.ofSeconds(3)));
                reports.add(second.await(Duration.ofSeconds(3)));
            } else {
                reports.add(supervisor.submit(request(seed.id() + "-a-" + replay), CancellationToken.none())
                        .await(Duration.ofSeconds(3)));
                reports.add(supervisor.submit(request(seed.id() + "-b-" + replay), CancellationToken.none())
                        .await(Duration.ofSeconds(3)));
            }
            long wall = System.nanoTime() - started;
            ChildTaskHandle cancellation = supervisor.submit(request(seed.id() + "-cancel-" + replay), CancellationToken.none());
            assertThat(cancelEntered.await(1, TimeUnit.SECONDS)).isTrue();
            long cancelStarted = System.nanoTime();
            cancellation.cancel();
            assertThat(cancellation.await(Duration.ofSeconds(1)).status()).isEqualTo(ChildTaskStatus.CANCELLED);
            long cancelLatency = System.nanoTime() - cancelStarted;
            int calls = reports.stream().mapToInt(ChildTaskReport::toolCalls).sum();
            return new Metric(reports.stream().allMatch(r -> r.status() == ChildTaskStatus.SUCCEEDED),
                    constraints.get(), reports.stream().mapToInt(ChildTaskReport::modelTurns).sum(), calls,
                    Math.max(1, inputCharacters.get() / 4), wall, cancelLatency,
                    executions.get() == 2 ? 0 : 1, unapproved.get(), calls - lifecycleAfterTool.get());
        }
    }

    private ChildRuntimeScope scope(Seed seed, Path fixture, ChildTaskRequest childRequest,
            AtomicInteger constraints, AtomicInteger executions, AtomicInteger lifecycleAfterTool,
            AtomicInteger unapproved, AtomicLong inputCharacters, CountDownLatch cancelEntered) {
        if (childRequest.prompt().contains("-cancel-")) {
            ModelGateway blocking = request -> {
                inputCharacters.addAndGet(request.toString().length());
                cancelEntered.countDown();
                try { Thread.sleep(Duration.ofSeconds(5)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return ModelTurn.text("cancelled");
            };
            return runtimeScope(blocking, List.of(), ignored -> { });
        }
        AtomicInteger turn = new AtomicInteger();
        ModelGateway gateway = request -> {
            inputCharacters.addAndGet(request.toString().length());
            if (turn.getAndIncrement() == 0) {
                try { Thread.sleep(seed.delayMillis()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return ModelTurn.tools(List.of(new ToolCall("call-" + childRequest.delegationId().value(),
                        "read_fixture", JsonObject.empty())));
            }
            return ModelTurn.text("verified");
        };
        AgentTool read = new AgentTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("read_fixture", "read deterministic fixture", "{}",
                        ToolEffect.READ_WORKSPACE, ToolSource.BUILT_IN, false,
                        Duration.ofSeconds(1), "text/plain", 1024);
            }
            @Override public ToolValidationResult validate(JsonObject arguments) { return ToolValidationResult.validResult(); }
            @Override public ToolExecutionOutcome execute(ToolInvocation invocation) {
                executions.incrementAndGet();
                try {
                    String actual = Files.readString(fixture);
                    if (actual.equals(seed.expected())) constraints.incrementAndGet();
                    return ToolExecutionOutcome.success(actual);
                } catch (Exception failure) {
                    return ToolExecutionOutcome.failure(ToolError.of(ToolErrorCode.EXECUTION_FAILED, "fixture read failed"));
                }
            }
        };
        return runtimeScope(gateway, List.of(read), event -> {
            if (event.event() instanceof LifecycleEvent.AfterTool) lifecycleAfterTool.incrementAndGet();
        });
    }

    private static ChildRuntimeScope runtimeScope(ModelGateway gateway, List<AgentTool> tools,
            java.util.function.Consumer<AgentEventEnvelope> events) {
        AgentIdGenerator ids = new AgentIdGenerator() {
            private final AtomicInteger value = new AtomicInteger();
            @Override public SessionId newSessionId() { return new SessionId("eval-session-" + value.incrementAndGet()); }
            @Override public RunId newRunId() { return new RunId("eval-run-" + value.incrementAndGet()); }
        };
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(Clock.systemUTC(), events::accept);
        InMemorySessionStore sessions = new InMemorySessionStore(ids, lifecycle);
        ToolRegistry registry = new ToolRegistry(tools);
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry,
                (invocation, definition) -> PermissionOutcome.of(PermissionDecision.ALLOW,
                        PermissionReason.EFFECT_DEFAULT, PermissionSelector.toolWide(definition.name(), definition.source())),
                (invocation, definition, outcome) -> ApprovalResponse.deny(), lifecycle);
        AgentRuntime runtime = new AgentRuntime(sessions, ids, gateway, new DefaultContextAssembler(), registry, pipeline, lifecycle);
        AgentSession session = sessions.create(new SessionSpec("eval", Map.of()));
        return new ChildRuntimeScope(runtime, session.id(), () -> sessions.close(session.id()));
    }

    private static AgentDefinitionCatalog catalog() {
        return new AgentDefinitionCatalog() {
            @Override public Optional<AgentDefinitionSnapshot> find(AgentDefinitionId id) {
                return id.equals(DEFINITION.id()) ? Optional.of(DEFINITION) : Optional.empty();
            }
            @Override public List<AgentDefinitionSnapshot> snapshots() { return List.of(DEFINITION); }
        };
    }

    private static ChildTaskRequest request(String id) {
        return new ChildTaskRequest(new DelegationId(id), DEFINITION.id(), id,
                Set.of("read_fixture"), CHILD_BUDGET, true, 1, false);
    }
    private static double completion(List<Metric> values) {
        return values.stream().filter(Metric::completed).count() / (double) values.size();
    }
    private static double medianWall(List<Metric> values) {
        List<Long> sorted = values.stream().map(Metric::wallNanos).sorted(Comparator.naturalOrder()).toList();
        return (sorted.get(14) + sorted.get(15)) / 2.0;
    }
    private static double medianTokens(List<Metric> values) {
        List<Long> sorted = values.stream().map(Metric::estimatedInputTokens).sorted().toList();
        return (sorted.get(14) + sorted.get(15)) / 2.0;
    }
    private record Seed(String id, String expected, long delayMillis) { }
    private record Metric(boolean completed, int constraintsPreserved, int modelTurns, int toolCalls,
            long estimatedInputTokens, long wallNanos, long cancelLatencyNanos, int fileConflicts,
            int unapprovedSideEffects, int pipelineBypasses) { }
}
