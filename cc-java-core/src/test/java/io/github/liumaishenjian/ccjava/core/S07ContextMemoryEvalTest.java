package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.ContextReductionStrategy;
import io.github.liumaishenjian.ccjava.domain.ContextSummaryMessage;
import io.github.liumaishenjian.ccjava.domain.ContextUsageView;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.SummaryCandidate;
import io.github.liumaishenjian.ccjava.domain.SystemMessage;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 以项目自有的确定性 Fake 验证 S07 多回合长会话 Projection 的结构阈值。
 *
 * <p>每个样本先完成三个独立 Tool Call/Result 批次，再以未压缩控制组和真实
 * {@link ContextPreparationService} 比较同一 Canonical 历史。Fake 仅检查独立编写的事实、
 * 硬约束和完成标记是否仍位于发往模型的请求中，不评估真实模型语义质量。</p>
 */
class S07ContextMemoryEvalTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC);
    @Test
    void deterministicMultiTurnSessionsRetainRequiredMarkersAndReduceMedianInputAtLeastThirtyPercent() {
        List<Scenario> scenarios = List.of(
                new Scenario("alpha", "FACT: release-window=friday", "CONSTRAINT: do-not-delete", "TASK: alpha-complete",
                        new ContextCapacity("offline-c1", 7_000, 20, 20), 40, "payload-".repeat(220)),
                new Scenario("bravo", "FACT: owner=platform", "CONSTRAINT: preserve-tool-pairs", "TASK: bravo-complete",
                        new ContextCapacity("offline-c2", 7_000, 20, 20), 40, "payload-".repeat(220)),
                new Scenario("charlie", "FACT: mode=offline", "CONSTRAINT: no-network", "TASK: charlie-complete",
                        new ContextCapacity("offline-c3", 5_000, 20, 20), 10_000, "small"));
        List<Measurement> measurements = scenarios.stream().map(this::compare).toList();

        assertThat(measurements).hasSameSizeAs(scenarios);
        assertThat(measurements).allSatisfy(measurement -> {
            assertThat(measurement.completedToolBatches()).isGreaterThanOrEqualTo(3);
            assertThat(measurement.canonicalUnchanged()).isTrue();
            assertThat(measurement.completeToolPairs()).isTrue();
            assertThat(measurement.baselineCompleted()).isTrue();
            assertThat(measurement.candidateCompleted()).isTrue();
            assertThat(measurement.enteredReduction()).isTrue();
            assertThat(measurement.appliedStrategies()).isNotEmpty();
            assertThat(measurement.candidateTokens()).isLessThan(measurement.baselineTokens());
        });
        List<Long> reductions = measurements.stream().map(Measurement::reductionPercent).sorted().toList();
        long median = reductions.get(reductions.size() / 2);
        assertThat(measurements).anySatisfy(measurement -> assertThat(measurement.appliedStrategies())
                .contains(ContextReductionStrategy.LARGE_PAYLOAD_REDUCTION));
        assertThat(measurements).anySatisfy(measurement -> assertThat(measurement.appliedStrategies())
                .contains(ContextReductionStrategy.ROLLING_MEMORY));
        assertThat(median).as("S07 offline median estimated input-token reduction").isGreaterThanOrEqualTo(30);
        System.out.println(metricsLine(measurements, median));
    }

    private Measurement compare(Scenario scenario) {
        Run baseline = run(scenario, ContextPreparationService.noop());
        List<ContextUsageView> usage = new ArrayList<>();
        Run candidate = run(scenario, preparation(scenario, usage));
        List<ContextReductionStrategy> strategies = usage.getLast().appliedReductions().stream()
                .map(reduction -> reduction.strategy()).toList();
        return new Measurement(
                scenario.name(),
                candidate.completedToolBatches(),
                candidate.canonicalUnchanged(),
                completeToolPairs(candidate.request().messages()),
                completes(baseline.request(), scenario),
                completes(candidate.request(), scenario),
                !strategies.isEmpty(),
                strategies,
                baseline.tokens(),
                candidate.tokens());
    }

    private Run run(Scenario scenario, ContextPreparationService preparation) {
        SequentialAgentIdGenerator ids = new SequentialAgentIdGenerator();
        LifecycleDispatcher lifecycle = new LifecycleDispatcher(CLOCK, AgentEventSink.noop());
        InMemorySessionStore sessions = new InMemorySessionStore(ids, lifecycle);
        RecordingAgentTool tool = RecordingAgentTool.succeeding("evidence", scenario.toolPayload());
        ToolRegistry tools = new ToolRegistry(List.of(tool));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                tools,
                (invocation, definition) -> io.github.liumaishenjian.ccjava.domain.PermissionOutcome.of(
                        io.github.liumaishenjian.ccjava.domain.PermissionDecision.ALLOW,
                        io.github.liumaishenjian.ccjava.domain.PermissionReason.EFFECT_DEFAULT,
                        io.github.liumaishenjian.ccjava.domain.PermissionSelector.toolWide(definition.name(), definition.source())),
                (invocation, definition, outcome) -> io.github.liumaishenjian.ccjava.domain.ApprovalResponse.allowOnce(),
                lifecycle);
        var session = sessions.create(SessionSpec.of("system"));
        for (int turn = 1; turn <= 3; turn++) {
            String historicalMarkers = turn == 1 ? scenario.fact() + " " + scenario.constraint() + " " : "";
            ScriptedModelGateway seed = ScriptedModelGateway.of(
                    ModelTurn.tools(List.of(new ToolCall(
                            scenario.name() + "-history-" + turn, "evidence", new JsonObject(Map.of())))),
                    ModelTurn.text(historicalMarkers + "completed historical batch " + turn + " "
                            + "narrative-".repeat(160)));
            new AgentRuntime(sessions, ids, seed, new DefaultContextAssembler(), tools, pipeline, lifecycle)
                    .run(session.id(), new AgentRunRequest(
                            new UserMessage("HISTORY TURN " + turn + " completed"), AgentLimits.DEFAULT));
        }
        List<AgentMessage> canonicalBefore = session.messages();
        List<ModelRequest> requests = new ArrayList<>();
        ModelGateway oracle = request -> {
            requests.add(request);
            return ModelTurn.text(completes(request, scenario) ? scenario.completion() : "oracle-rejected");
        };
        new AgentRuntime(sessions, ids, oracle, new DefaultContextAssembler(), tools, pipeline, lifecycle,
                SessionJournal.noop(), preparation)
                .run(session.id(), new AgentRunRequest(new UserMessage(scenario.completion()), AgentLimits.DEFAULT));
        ModelRequest request = requests.getFirst();
        boolean canonicalUnchanged = session.messages().subList(0, canonicalBefore.size()).equals(canonicalBefore);
        long completedBatches = canonicalBefore.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(assistant -> !assistant.toolCalls().isEmpty())
                .count();
        return new Run(request, canonicalUnchanged, completedBatches,
                new CodePointContextTokenEstimator().estimate(request.messages(), scenario.capacity()).totalTokens());
    }

    private ContextPreparationService preparation(Scenario scenario, List<ContextUsageView> usage) {
        return new ContextPreparationService(new ContextPreparationConfig(
                scenario.capacity(), scenario.largePayloadThreshold(), 2, 1_000, 300),
                (request, cancellation) -> Optional.of(candidate(request)), usage::add);
    }

    private SummaryCandidate candidate(io.github.liumaishenjian.ccjava.domain.SummaryRequest request) {
        String summary = "FACT: release-window=friday CONSTRAINT: do-not-delete "
                + "FACT: owner=platform CONSTRAINT: preserve-tool-pairs "
                + "FACT: mode=offline CONSTRAINT: no-network completed history";
        int bytes = summary.getBytes(StandardCharsets.UTF_8).length;
        return new SummaryCandidate(request.tier(), summary, request.sourceRevision(), request.sourceMessageIds(),
                bytes, Math.min(summary.codePointCount(0, summary.length()), request.maxOutputTokens()));
    }

    private boolean completes(ModelRequest request, Scenario scenario) {
        String joined = request.messages().stream().map(this::text).reduce("", String::concat);
        return joined.contains(scenario.fact()) && joined.contains(scenario.constraint()) && joined.contains(scenario.completion());
    }

    private String text(AgentMessage message) {
        if (message instanceof SystemMessage value) return value.content();
        if (message instanceof UserMessage value) return value.content();
        if (message instanceof AssistantMessage value) return value.text();
        if (message instanceof ToolResultMessage value) return value.result().content();
        if (message instanceof ContextSummaryMessage value) return value.content();
        return "";
    }

    private boolean completeToolPairs(List<AgentMessage> messages) {
        List<String> pending = new ArrayList<>();
        for (AgentMessage message : messages) {
            if (message instanceof AssistantMessage assistant && !assistant.toolCalls().isEmpty()) {
                if (!pending.isEmpty()) return false;
                pending.addAll(assistant.toolCalls().stream().map(ToolCall::id).toList());
            } else if (message instanceof ToolResultMessage result) {
                if (pending.isEmpty() || !pending.removeFirst().equals(result.result().callId())) return false;
            } else if (!pending.isEmpty()) return false;
        }
        return pending.isEmpty();
    }

    private String metricsLine(List<Measurement> measurements, long median) {
        return "S07_D4_METRICS " + measurements.stream().map(measurement -> measurement.name()
                + " baseline=" + measurement.baselineTokens()
                + " candidate=" + measurement.candidateTokens()
                + " strategies=" + measurement.appliedStrategies()
                + " reduction=" + measurement.reductionPercent() + "%")
                .reduce((left, right) -> left + "; " + right).orElseThrow()
                + "; median=" + median + "%";
    }

    private record Scenario(String name, String fact, String constraint, String completion,
                            ContextCapacity capacity, long largePayloadThreshold, String toolPayload) { }
    private record Run(ModelRequest request, boolean canonicalUnchanged, long completedToolBatches, long tokens) { }
    private record Measurement(String name, long completedToolBatches, boolean canonicalUnchanged,
                               boolean completeToolPairs, boolean baselineCompleted, boolean candidateCompleted,
                               boolean enteredReduction, List<ContextReductionStrategy> appliedStrategies,
                               long baselineTokens, long candidateTokens) {
        long reductionPercent() { return (baselineTokens - candidateTokens) * 100 / baselineTokens; }
    }
}
