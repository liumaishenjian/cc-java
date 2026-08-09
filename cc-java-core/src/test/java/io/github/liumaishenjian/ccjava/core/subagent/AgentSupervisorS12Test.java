package io.github.liumaishenjian.ccjava.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.subagent.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** S12 Supervisor 的确定性隔离、预算、并发、取消与隐私回归。 */
class AgentSupervisorS12Test {
    private static final ChildBudget BUDGET = new ChildBudget(2, 0, 1000, 256, Duration.ofSeconds(5));
    private static final AgentDefinitionSnapshot DEFINITION = new AgentDefinitionSnapshot(
            new AgentDefinitionId("research"), "readonly", "isolated", Set.of(), PermissionMode.PLAN,
            "fake", BUDGET, false, "0".repeat(64), "project");

    @Test
    void reusesAgentRuntimeAndReturnsPrivacySafeDeterministicReport() throws Exception {
        AtomicInteger runtimeCalls = new AtomicInteger();
        ChildRuntimeScopeFactory factory = (definition, request, cancellation) -> scope(request, runtimeCalls, null, null);
        try (AgentSupervisor supervisor = supervisor(factory, new ChildBudget(4, 0, 2000, 512, Duration.ofSeconds(10)), 2, 2)) {
            ChildTaskReport report = supervisor.submit(request("secret prompt C:\\private\\token.txt", false), CancellationToken.none())
                    .await(Duration.ofSeconds(2));
            assertThat(report.status()).isEqualTo(ChildTaskStatus.SUCCEEDED);
            assertThat(report.summary()).isEqualTo("completed; modelTurns=1; toolCalls=0")
                    .doesNotContain("secret", "private", "token.txt");
            assertThat(runtimeCalls).hasValue(1);
        }
    }

    @Test
    void reservesBudgetAtomicallyAndRetainsActualConsumptionAfterTerminal() throws Exception {
        CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        ChildRuntimeScopeFactory factory = (definition, request, cancellation) -> scope(request, new AtomicInteger(), entered, release);
        try (AgentSupervisor supervisor = supervisor(factory, BUDGET, 1, 1)) {
            ChildTaskHandle first = supervisor.submit(request("one", true), CancellationToken.none());
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> supervisor.submit(request("two", true), CancellationToken.none()))
                    .isInstanceOf(RejectedExecutionException.class).hasMessageContaining("预算不足");
            release.countDown();
            assertThat(first.await(Duration.ofSeconds(2)).status()).isEqualTo(ChildTaskStatus.SUCCEEDED);
            assertThatThrownBy(() -> supervisor.submit(request("three", false), CancellationToken.none()))
                    .isInstanceOf(RejectedExecutionException.class)
                    .hasMessageContaining("预算不足");
        }
    }

    @Test
    void enforcesSharedActiveLimitAndCancellationHasOneTerminal() throws Exception {
        AtomicInteger active = new AtomicInteger(); AtomicInteger maximum = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1); CountDownLatch entered = new CountDownLatch(2);
        ChildRuntimeScopeFactory factory = (definition, request, cancellation) -> blockingScope(request, active, maximum, entered, release);
        try (AgentSupervisor supervisor = supervisor(factory,
                new ChildBudget(8, 0, 4000, 1024, Duration.ofSeconds(20)), 2, 2)) {
            ChildTaskHandle one = supervisor.submit(request("one", true), CancellationToken.none());
            ChildTaskHandle two = supervisor.submit(request("two", true), CancellationToken.none());
            ChildTaskHandle queued = supervisor.submit(request("queued", true), CancellationToken.none());
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(maximum).hasValue(2);
            assertThat(queued.cancel()).isTrue();
            assertThat(queued.await(Duration.ofSeconds(1)).status()).isEqualTo(ChildTaskStatus.CANCELLED);
            release.countDown(); one.await(Duration.ofSeconds(2)); two.await(Duration.ofSeconds(2));
            assertThat(maximum).hasValue(2);
        }
    }

    @Test
    void registersNoReplayRecoveryAndProjectsStopContextWithoutChangingTerminal() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> projected = new java.util.concurrent.atomic.AtomicReference<>();
        ChildTaskLifecycle lifecycle = new ChildTaskLifecycle() {
            @Override public Optional<String> beforeStart(ChildTaskRequest request, CancellationToken token) {
                return Optional.empty();
            }
            @Override public Optional<String> afterTerminal(ChildTaskReport report) {
                return Optional.of("bounded-stop-context");
            }
        };
        try (AgentSupervisor supervisor = new AgentSupervisor(catalog(),
                (definition, request, cancellation) -> scope(request, new AtomicInteger(), null, null),
                new ChildBudgetLedger(BUDGET), AgentDefinitionNarrower.identity(), ChildTaskJournal.noop(),
                ChildTaskObserver.noop(), lifecycle, projected::set, Clock.systemUTC(), 1, 1, 2)) {
            ChildTaskReport completed = supervisor.submit(request("one", false), CancellationToken.none())
                    .await(Duration.ofSeconds(2));
            assertThat(completed.status()).isEqualTo(ChildTaskStatus.SUCCEEDED);
            assertThat(projected).hasValue("bounded-stop-context");

            ChildTaskReport recovered = new ChildTaskReport(new ChildTaskId("task-recovered"),
                    DEFINITION.id(), ChildTaskStatus.INTERRUPTED_UNKNOWN,
                    ChildTaskFailureCode.INTERRUPTED_UNKNOWN, 0, 0, 0, Duration.ZERO,
                    "interrupted_unknown", false, Optional.empty());
            supervisor.registerRecovered(recovered);
            assertThat(supervisor.find(recovered.taskId()).orElseThrow().inspect()).isEqualTo(recovered);
            assertThat(supervisor.find(recovered.taskId()).orElseThrow().cancel()).isFalse();
        }
    }

    @Test
    void slowBackgroundObserverDoesNotBlockTerminalAndCloseLeavesNoOrphan() throws Exception {
        CountDownLatch observerEntered = new CountDownLatch(1);
        CountDownLatch observerRelease = new CountDownLatch(1);
        ChildTaskObserver observer = report -> {
            observerEntered.countDown();
            try { observerRelease.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        };
        AgentSupervisor supervisor = new AgentSupervisor(catalog(),
                (definition, request, cancellation) -> scope(request, new AtomicInteger(), null, null),
                new ChildBudgetLedger(new ChildBudget(4, 0, 2000, 512, Duration.ofSeconds(10))),
                AgentDefinitionNarrower.identity(), ChildTaskJournal.noop(), observer, ChildTaskLifecycle.noop(),
                Clock.systemUTC(), 1, 2, 2);
        long started = System.nanoTime();
        ChildTaskReport terminal = supervisor.submit(request("background-observer", true), CancellationToken.none())
                .await(Duration.ofSeconds(1));
        assertThat(terminal.status()).isEqualTo(ChildTaskStatus.SUCCEEDED);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(observerEntered.await(1, TimeUnit.SECONDS)).isTrue();
        observerRelease.countDown();
        supervisor.close();
    }

    @Test
    void startHookBlockAndStopHookFailurePreserveExactlyOneTerminal() throws Exception {
        AtomicInteger runtimeCreations = new AtomicInteger();
        ChildTaskLifecycle blocking = new ChildTaskLifecycle() {
            @Override public Optional<String> beforeStart(ChildTaskRequest request, CancellationToken token) {
                throw new ChildTaskStartBlockedException();
            }
            @Override public Optional<String> afterTerminal(ChildTaskReport report) {
                throw new IllegalStateException("stop observer failure");
            }
        };
        try (AgentSupervisor supervisor = new AgentSupervisor(catalog(),
                (definition, request, cancellation) -> { runtimeCreations.incrementAndGet(); return runtimeScope(ignored -> ModelTurn.text("bad")); },
                new ChildBudgetLedger(BUDGET), AgentDefinitionNarrower.identity(), ChildTaskJournal.noop(),
                ChildTaskObserver.noop(), blocking, Clock.systemUTC(), 1, 1, 2)) {
            ChildTaskReport report = supervisor.submit(request("hook-block", false), CancellationToken.none())
                    .await(Duration.ofSeconds(1));
            assertThat(report.status()).isEqualTo(ChildTaskStatus.FAILED);
            assertThat(report.failureCode()).isEqualTo(ChildTaskFailureCode.START_HOOK_BLOCKED);
            assertThat(runtimeCreations).hasValue(0);
        }
    }

    @Test
    void rejectsHookWideningBeforeRuntimeCreation() {
        AtomicInteger created = new AtomicInteger();
        AgentDefinitionNarrower widening = (original, request) -> new AgentDefinitionSnapshot(original.id(),
                original.description(), original.instructions(), Set.of("write_file"), PermissionMode.DEFAULT,
                original.modelName(), original.budget(), false, original.contentDigest(), original.sourceKind());
        try (AgentSupervisor supervisor = new AgentSupervisor(catalog(),
                (d, r, c) -> { created.incrementAndGet(); throw new AssertionError(); }, new ChildBudgetLedger(BUDGET),
                widening, ChildTaskJournal.noop(), ChildTaskObserver.noop(), ChildTaskLifecycle.noop(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1, 1, 2)) {
            assertThatThrownBy(() -> supervisor.submit(request("widen", false), CancellationToken.none()))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(created).hasValue(0);
        }
    }

    private static AgentSupervisor supervisor(ChildRuntimeScopeFactory factory, ChildBudget total, int active, int queue) {
        return new AgentSupervisor(catalog(), factory,
                new ChildBudgetLedger(total), AgentDefinitionNarrower.identity(), ChildTaskJournal.noop(),
                ChildTaskObserver.noop(), ChildTaskLifecycle.noop(), Clock.systemUTC(), active, queue, 2);
    }

    private static AgentDefinitionCatalog catalog() {
        return new AgentDefinitionCatalog() {
            @Override public Optional<AgentDefinitionSnapshot> find(AgentDefinitionId id) {
                return id.equals(DEFINITION.id()) ? Optional.of(DEFINITION) : Optional.empty();
            }
            @Override public List<AgentDefinitionSnapshot> snapshots() { return List.of(DEFINITION); }
        };
    }

    private static ChildTaskRequest request(String prompt, boolean background) {
        return new ChildTaskRequest(new DelegationId("delegation-" + Integer.toUnsignedString(prompt.hashCode())),
                DEFINITION.id(), prompt, Set.of(), BUDGET, background, 1, false);
    }

    private static ChildRuntimeScope scope(ChildTaskRequest request, AtomicInteger calls,
            CountDownLatch entered, CountDownLatch release) {
        ModelGateway gateway = modelRequest -> {
            calls.incrementAndGet();
            if (entered != null) entered.countDown();
            if (release != null) try { release.await(); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); return ModelTurn.text("cancelled");
            }
            return ModelTurn.text(request.prompt());
        };
        return runtimeScope(gateway);
    }

    private static ChildRuntimeScope blockingScope(ChildTaskRequest request, AtomicInteger active,
            AtomicInteger maximum, CountDownLatch entered, CountDownLatch release) {
        ModelGateway gateway = ignored -> {
            int now = active.incrementAndGet(); maximum.accumulateAndGet(now, Math::max); entered.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { active.decrementAndGet(); }
            return ModelTurn.text("done");
        };
        return runtimeScope(gateway);
    }

    private static ChildRuntimeScope runtimeScope(ModelGateway gateway) {
        AgentIdGenerator ids = new AgentIdGenerator() {
            private final AtomicInteger value = new AtomicInteger();
            public SessionId newSessionId() { return new SessionId("child-session-" + value.incrementAndGet()); }
            public RunId newRunId() { return new RunId("child-run-" + value.incrementAndGet()); }
        };
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(Clock.systemUTC(), ignored -> { });
        InMemorySessionStore sessions = new InMemorySessionStore(ids, lifecycle);
        ToolRegistry registry = new ToolRegistry(List.of());
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry,
                (invocation, definition) -> PermissionOutcome.of(PermissionDecision.ALLOW,
                        PermissionReason.EFFECT_DEFAULT,
                        PermissionSelector.toolWide(definition.name(), definition.source())),
                (invocation, definition, outcome) -> ApprovalResponse.deny(), lifecycle);
        AgentRuntime runtime = new AgentRuntime(sessions, ids, gateway, new DefaultContextAssembler(), registry, pipeline, lifecycle);
        AgentSession session = sessions.create(new SessionSpec("child", Map.of()));
        return new ChildRuntimeScope(runtime, session.id(), () -> sessions.close(session.id()));
    }
}
