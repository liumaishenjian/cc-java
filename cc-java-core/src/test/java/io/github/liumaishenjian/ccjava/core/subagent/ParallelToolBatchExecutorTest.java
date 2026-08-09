package io.github.liumaishenjian.ccjava.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** S12 TOOL-15 的真实 ToolExecutionPipeline 并发/退化可证伪测试。 */
class ParallelToolBatchExecutorTest {

    @Test
    void fourSlowReadToolsUseOnePipelinePreserveOrderAndGainAtLeastFortyPercent() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<String> completed = new CopyOnWriteArrayList<>();
        AgentTool read = slowTool("read", ToolEffect.READ_WORKSPACE, active, maximum, completed);
        Fixture fixture = fixture(List.of(read));
        List<ToolCall> calls = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> new ToolCall("call-" + index, "read", JsonObject.empty())).toList();

        long started = System.nanoTime();
        List<ToolResult> results;
        try (var executor = new ParallelToolBatchExecutor(fixture.registry, fixture.pipeline, Set.of("read"), 4)) {
            results = executor.executeSafeBatch(fixture.session, new RunId("run-parallel"),
                    List.of(1, 2, 3, 4), calls, CancellationToken.none());
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMillis).isLessThan(480); // sequential baseline is at least 4 * 200ms
        assertThat(maximum).hasValueGreaterThanOrEqualTo(2);
        assertThat(results).extracting(ToolResult::callId)
                .containsExactly("call-0", "call-1", "call-2", "call-3");
        assertThat(results).allMatch(result -> result.status() == ToolResultStatus.SUCCESS);
        assertThat(fixture.lifecycleEvents).filteredOn(event -> event.event() instanceof LifecycleEvent.BeforeTool)
                .hasSize(4);
        assertThat(fixture.lifecycleEvents).filteredOn(event -> event.event() instanceof LifecycleEvent.AfterTool)
                .hasSize(4);
    }

    @Test
    void oneUnsafeCallDegradesWholeBatchToOriginalSequentialOrder() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<String> completed = new CopyOnWriteArrayList<>();
        AgentTool read = slowTool("read", ToolEffect.READ_WORKSPACE, active, maximum, completed);
        AgentTool write = slowTool("write", ToolEffect.SYSTEM_OR_DESTRUCTIVE, active, maximum, completed);
        Fixture fixture = fixture(List.of(read, write));
        List<ToolCall> calls = List.of(
                new ToolCall("call-a", "read", JsonObject.empty()),
                new ToolCall("call-b", "write", JsonObject.empty()),
                new ToolCall("call-c", "read", JsonObject.empty()));

        try (var executor = new ParallelToolBatchExecutor(fixture.registry, fixture.pipeline, Set.of("read"), 4)) {
            assertThat(executor.executeSafeBatch(fixture.session, new RunId("run-sequential"),
                    List.of(1, 2, 3), calls, CancellationToken.none()))
                    .extracting(ToolResult::callId).containsExactly("call-a", "call-b", "call-c");
        }
        assertThat(maximum).hasValue(1);
        assertThat(completed).containsExactly("read", "write", "read");
    }

    private static AgentTool slowTool(String name, ToolEffect effect, AtomicInteger active,
            AtomicInteger maximum, List<String> completed) {
        return new AgentTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition(name, name, "{}", effect, ToolSource.BUILT_IN,
                        false, Duration.ofSeconds(2), "text/plain", 1024);
            }
            @Override public ToolValidationResult validate(JsonObject arguments) {
                return ToolValidationResult.validResult();
            }
            @Override public ToolExecutionOutcome execute(ToolInvocation invocation) {
                int count = active.incrementAndGet(); maximum.accumulateAndGet(count, Math::max);
                try { Thread.sleep(200); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { active.decrementAndGet(); }
                completed.add(name);
                return ToolExecutionOutcome.success(name);
            }
        };
    }

    private static Fixture fixture(List<AgentTool> tools) {
        AgentIdGenerator ids = new AgentIdGenerator() {
            public SessionId newSessionId() { return new SessionId("session-parallel"); }
            public RunId newRunId() { return new RunId("run-generated"); }
        };
        List<AgentEventEnvelope> events = new CopyOnWriteArrayList<>();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(Clock.systemUTC(), events::add);
        InMemorySessionStore store = new InMemorySessionStore(ids, lifecycle);
        AgentSession session = store.create(SessionSpec.of("parallel"));
        ToolRegistry registry = new ToolRegistry(tools);
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(registry,
                (invocation, definition) -> PermissionOutcome.of(PermissionDecision.ALLOW,
                        PermissionReason.EFFECT_DEFAULT,
                        PermissionSelector.toolWide(definition.name(), definition.source())),
                (invocation, definition, outcome) -> ApprovalResponse.allowOnce(), lifecycle);
        return new Fixture(registry, pipeline, session, events);
    }

    private record Fixture(ToolRegistry registry, ToolExecutionPipeline pipeline, AgentSession session,
            List<AgentEventEnvelope> lifecycleEvents) { }
}
