package io.github.liumaishenjian.ccjava.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.cli.daemon.StableProtocolHandler;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessAgentApplicationService;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ContextPreparationConfig;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.core.eval.AgentEvalAggregator;
import io.github.liumaishenjian.ccjava.core.eval.EvalRun;
import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.AgentRunRequest;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.ContextSummaryMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.SummaryCandidate;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.ToolResultStatus;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.protocol.CapabilityToken;
import io.github.liumaishenjian.ccjava.protocol.ProtocolEnvelope;
import io.github.liumaishenjian.ccjava.protocol.ProtocolFeature;
import io.github.liumaishenjian.ccjava.protocol.ProtocolMessageKind;
import io.github.liumaishenjian.ccjava.protocol.ProtocolVersion;
import io.github.liumaishenjian.ccjava.protocol.StableProtocolCodec;
import io.github.liumaishenjian.ccjava.sdk.CcJavaSdkClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 12 个公开 seed 各自驱动明确的 Application/SDK/stable protocol 场景，每项重复五次。
 *
 * <p>每个 Measurement 都来自生产 composition 返回的 RunResult、模型实际收到的 ToolResult、
 * lifecycle event 或 stable envelope；不得直接构造 success、Usage、cache、cost 或 provider 标签。</p>
 */
class S14UnifiedEvalTest {
    @TempDir Path temp;

    @Test void executesRegisteredSeedScenariosAndWritesTruthfulReport() throws Exception {
        Map<String, SeedScenario> scenarios = scenarios();
        assertThat(scenarios).hasSize(12);
        List<EvalRun> runs = new ArrayList<>();
        Map<String, SeedTotals> totals = new LinkedHashMap<>();
        for (var entry : scenarios.entrySet()) {
            SeedTotals total = new SeedTotals();
            totals.put(entry.getKey(), total);
            for (int repeat = 0; repeat < 5; repeat++) {
                long started = System.nanoTime();
                Measurement result = entry.getValue().run(repeat);
                Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
                total.accept(result, elapsed);
                runs.add(new EvalRun(entry.getKey(), result.route(), result.success(), -1, -1,
                        result.modelTurns(), result.toolCalls(), elapsed, result.violations(), false));
            }
        }
        var report = new AgentEvalAggregator().aggregate(runs);
        Path artifact = Path.of("target", "s14-unified-eval.json");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, json(report, totals), StandardCharsets.UTF_8);
        assertThat(report.runs()).isEqualTo(60);
        assertThat(report.successRate()).isEqualTo(1.0);
        assertThat(report.violations()).isZero();
        assertThat(report.medianKnownInputTokens()).isEqualTo(-1);
        assertThat(totals.values()).allSatisfy(total -> assertThat(total.runs).isEqualTo(5));
        assertThat(totals.get("application-tool-loop").toolCalls).isGreaterThan(0);
        assertThat(totals.get("application-id-pairing").modelTurns).isGreaterThan(5);
        assertThat(totals.get("application-permission-recovery").toolCalls).isGreaterThan(0);
        assertThat(totals.get("application-tool-failure-recovery").toolCalls).isGreaterThan(0);
        assertThat(totals.get("sdk-tool-loop").toolCalls).isGreaterThan(0);
    }

    private Map<String, SeedScenario> scenarios() {
        LinkedHashMap<String, SeedScenario> values = new LinkedHashMap<>();
        values.put("application-final", this::directFinal);
        values.put("application-tool-loop", this::builtInToolLoop);
        values.put("application-id-pairing", this::toolIdPairing);
        values.put("application-permission-recovery", this::permissionRecovery);
        values.put("application-tool-failure-recovery", this::toolFailureRecovery);
        values.put("application-cancel", this::cancelledRun);
        values.put("application-limit", this::turnLimit);
        values.put("application-context", this::contextPreparation);
        values.put("application-session-lifecycle", this::sessionLifecycle);
        values.put("sdk-tool-loop", this::sdkToolLoop);
        values.put("stable-run-events", repeat -> stableSurface(repeat, false));
        values.put("stable-idempotency", repeat -> stableSurface(repeat, true));
        return Map.copyOf(values);
    }

    private Measurement directFinal(int repeat) throws Exception {
        try (HeadlessRuntimeSession runtime = session("final", repeat, request -> ModelTurn.text("done"))) {
            runtime.open();
            AgentRunResult result = runtime.run("direct final");
            return measured("headless-application", result,
                    result.stopReason() == StopReason.COMPLETED && result.finalText().orElse("").contains("done"));
        }
    }

    private Measurement builtInToolLoop(int repeat) throws Exception {
        Path workspace = workspace("tool-loop", repeat);
        Files.writeString(workspace.resolve("evidence.txt"), "observed-evidence");
        AtomicReference<ToolResult> observed = new AtomicReference<>();
        ModelGateway gateway = request -> {
            Optional<ToolResult> result = latestToolResult(request);
            if (result.isEmpty()) return tools(new ToolCall("read-" + repeat, "read_file",
                    new JsonObject(Map.of("path", "evidence.txt"))));
            observed.set(result.orElseThrow());
            return ModelTurn.text("read complete");
        };
        try (HeadlessRuntimeSession runtime = session("tool-loop", repeat, workspace, gateway,
                SessionOpenRequest.create(), store("tool-loop", repeat), Duration.ofSeconds(3))) {
            runtime.open();
            AgentRunResult result = runtime.run("read evidence");
            ToolResult tool = observed.get();
            boolean valid = tool != null && tool.callId().equals("read-" + repeat)
                    && tool.toolName().equals("read_file") && tool.status() == ToolResultStatus.SUCCESS
                    && tool.content().contains("observed-evidence") && result.modelTurns() > 1
                    && result.toolCalls() > 0;
            return measured("headless-application", result, valid);
        }
    }

    private Measurement toolIdPairing(int repeat) throws Exception {
        Path workspace = workspace("id-pair", repeat);
        Files.writeString(workspace.resolve("a.txt"), "a");
        AtomicReference<List<ToolResult>> observed = new AtomicReference<>();
        ModelGateway gateway = request -> {
            List<ToolResult> results = toolResults(request);
            if (results.isEmpty()) return tools(
                    new ToolCall("list-" + repeat, "list_files", new JsonObject(Map.of("path", "."))),
                    new ToolCall("read-" + repeat, "read_file", new JsonObject(Map.of("path", "a.txt"))));
            observed.set(results);
            return ModelTurn.text("paired");
        };
        try (HeadlessRuntimeSession runtime = session("id-pair", repeat, workspace, gateway,
                SessionOpenRequest.create(), store("id-pair", repeat), Duration.ofSeconds(3))) {
            runtime.open();
            AgentRunResult result = runtime.run("pair calls");
            boolean valid = observed.get() != null
                    && observed.get().stream().map(ToolResult::callId).toList()
                            .equals(List.of("list-" + repeat, "read-" + repeat))
                    && result.modelTurns() > 1 && result.toolCalls() == 2;
            return measured("headless-application", result, valid);
        }
    }

    private Measurement permissionRecovery(int repeat) throws Exception {
        AtomicReference<ToolResult> observed = new AtomicReference<>();
        ModelGateway gateway = request -> {
            Optional<ToolResult> result = latestToolResult(request);
            if (result.isEmpty()) return tools(new ToolCall("write-" + repeat, "write_file",
                    new JsonObject(Map.of("path", "denied.txt", "content", "must-not-write"))));
            observed.set(result.orElseThrow());
            return ModelTurn.text("recovered after denial");
        };
        try (HeadlessRuntimeSession runtime = session("permission", repeat, gateway)) {
            runtime.open();
            AgentRunResult result = runtime.run("attempt denied write then recover");
            ToolResult tool = observed.get();
            boolean valid = tool != null && tool.callId().equals("write-" + repeat)
                    && tool.status() == ToolResultStatus.DENIED
                    && !Files.exists(workspace("permission", repeat).resolve("denied.txt"))
                    && result.stopReason() == StopReason.COMPLETED && result.modelTurns() > 1
                    && result.toolCalls() > 0;
            return measured("headless-permission-recovery", result, valid);
        }
    }

    private Measurement toolFailureRecovery(int repeat) throws Exception {
        List<ToolResult> observed = new ArrayList<>();
        ModelGateway gateway = request -> {
            List<ToolResult> results = toolResults(request);
            if (results.isEmpty()) return tools(new ToolCall("unknown-" + repeat, "not_registered", JsonObject.empty()));
            if (observed.isEmpty()) {
                observed.add(results.getLast());
                return tools(new ToolCall("missing-" + repeat, "read_file",
                        new JsonObject(Map.of("path", "missing.txt"))));
            }
            observed.add(results.getLast());
            return ModelTurn.text("recovered after failures");
        };
        try (HeadlessRuntimeSession runtime = session("tool-failure", repeat, gateway)) {
            runtime.open();
            AgentRunResult result = runtime.run("recover from tool failures");
            boolean valid = observed.size() == 2
                    && observed.stream().allMatch(value -> value.status() == ToolResultStatus.FAILURE)
                    && observed.stream().map(ToolResult::callId).toList()
                            .equals(List.of("unknown-" + repeat, "missing-" + repeat))
                    && result.stopReason() == StopReason.COMPLETED && result.modelTurns() == 3
                    && result.toolCalls() == 2;
            return measured("headless-tool-failure-recovery", result, valid);
        }
    }

    private Measurement cancelledRun(int repeat) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<AgentRunResult> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ModelGateway gateway = request -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return ModelTurn.text("late");
        };
        try (HeadlessRuntimeSession runtime = session("cancel", repeat, gateway)) {
            runtime.open();
            Thread runner = Thread.ofPlatform().start(() -> {
                try { result.set(runtime.run("cancel me")); } catch (Throwable thrown) { failure.set(thrown); }
            });
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            boolean targeted = runtime.cancelActive();
            release.countDown();
            runner.join(3_000);
            AgentRunResult actual = result.get();
            boolean valid = failure.get() == null && targeted && actual != null
                    && actual.stopReason() == StopReason.USER_CANCELLED;
            return measured("headless-cancel", actual, valid);
        }
    }

    private Measurement turnLimit(int repeat) throws Exception {
        ModelGateway gateway = request -> tools(new ToolCall("limit-" + repeat, "list_files",
                new JsonObject(Map.of("path", "."))));
        try (HeadlessRuntimeSession runtime = session("limit", repeat, gateway)) {
            runtime.open();
            AgentRunRequest request = new AgentRunRequest(new UserMessage("reach limit"),
                    new AgentLimits(1, 4, Duration.ofSeconds(2)), Optional.empty());
            AgentRunResult result = runtime.run(request, AgentEventSink.noop());
            return measured("headless-limit", result,
                    result.stopReason() == StopReason.TURN_LIMIT_REACHED
                            && result.modelTurns() == 1 && result.toolCalls() == 1);
        }
    }

    private Measurement contextPreparation(int repeat) throws Exception {
        Path workspace = workspace("context", repeat);
        Files.writeString(workspace.resolve("large.txt"), "payload-".repeat(2_000));
        AtomicReference<ModelRequest> second = new AtomicReference<>();
        ModelGateway gateway = request -> {
            if (latestToolResult(request).isEmpty()) return tools(new ToolCall("large-" + repeat,
                    "read_file", new JsonObject(Map.of("path", "large.txt"))));
            second.set(request);
            return ModelTurn.text("context prepared");
        };
        HeadlessRuntimeOptions options = contextOptions("context", repeat, workspace);
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(gateway, AgentEventSink.noop(), options,
                (invocation, definition, outcome) -> io.github.liumaishenjian.ccjava.domain.ApprovalResponse.deny(),
                (request, cancellation) -> Optional.of(new SummaryCandidate(request.tier(), "summary",
                        request.sourceRevision(), request.sourceMessageIds(), 7, 1)))) {
            runtime.open();
            AgentRunResult result = runtime.run("read large context");
            ToolResult prepared = latestToolResult(second.get()).orElseThrow();
            boolean valid = prepared.content().contains("C1 已缩减正文")
                    && !prepared.content().contains("payload-payload-")
                    && result.modelTurns() > 1 && result.toolCalls() > 0;
            return measured("headless-context", result, valid);
        }
    }

    private Measurement sessionLifecycle(int repeat) throws Exception {
        Path store = store("session", repeat);
        Path workspace = workspace("session", repeat);
        io.github.liumaishenjian.ccjava.domain.SessionId created;
        try (HeadlessRuntimeSession create = session("session-create", repeat, workspace,
                request -> ModelTurn.text("created"), SessionOpenRequest.create(), store, Duration.ofSeconds(3))) {
            created = create.open();
            assertThat(create.run("canonical create").stopReason()).isEqualTo(StopReason.COMPLETED);
        }
        AtomicReference<ModelRequest> continuedRequest = new AtomicReference<>();
        io.github.liumaishenjian.ccjava.domain.SessionId continued;
        try (HeadlessRuntimeSession runtime = session("session-continue", repeat, workspace,
                request -> { continuedRequest.set(request); return ModelTurn.text("continued"); },
                SessionOpenRequest.continueLatest(), store, Duration.ofSeconds(3))) {
            continued = runtime.open();
            assertThat(runtime.run("canonical continue").stopReason()).isEqualTo(StopReason.COMPLETED);
        }
        AtomicReference<ModelRequest> resumedRequest = new AtomicReference<>();
        io.github.liumaishenjian.ccjava.domain.SessionId resumed;
        try (HeadlessRuntimeSession runtime = session("session-resume", repeat, workspace,
                request -> { resumedRequest.set(request); return ModelTurn.text("resumed"); },
                SessionOpenRequest.resume(created), store, Duration.ofSeconds(3))) {
            resumed = runtime.open();
            assertThat(runtime.run("canonical resume").stopReason()).isEqualTo(StopReason.COMPLETED);
        }
        boolean valid = created.equals(continued) && created.equals(resumed)
                && userTexts(continuedRequest.get()).contains("canonical create")
                && userTexts(resumedRequest.get()).containsAll(List.of("canonical create", "canonical continue"));
        return new Measurement("canonical-session", valid, 3, 0, valid ? 0 : 1,
                StopReason.COMPLETED.name());
    }

    private Measurement sdkToolLoop(int repeat) throws Exception {
        AtomicReference<ToolResult> observed = new AtomicReference<>();
        ModelGateway gateway = request -> {
            Optional<ToolResult> result = latestToolResult(request);
            if (result.isEmpty()) return tools(new ToolCall("sdk-list-" + repeat, "list_files",
                    new JsonObject(Map.of("path", "."))));
            observed.set(result.orElseThrow());
            return ModelTurn.text("sdk done");
        };
        HeadlessRuntimeSession runtime = session("sdk", repeat, gateway);
        runtime.open();
        try (CcJavaSdkClient sdk = new CcJavaSdkClient(new HeadlessAgentApplicationService(runtime))) {
            AgentRunResult result = sdk.run(AgentRunRequest.of("sdk surface"), AgentEventSink.noop());
            boolean valid = observed.get() != null && observed.get().callId().equals("sdk-list-" + repeat)
                    && result.modelTurns() > 1 && result.toolCalls() > 0;
            return measured("java-sdk", result, valid);
        }
    }

    private Measurement stableSurface(int repeat, boolean replay) throws Exception {
        HeadlessRuntimeSession runtime = session("stable-" + replay, repeat, request -> ModelTurn.text("wire done"));
        runtime.open();
        CapabilityToken token = CapabilityToken.generate();
        try (StableProtocolHandler handler = new StableProtocolHandler(token,
                Set.of(ProtocolFeature.RUN, ProtocolFeature.DAEMON), new HeadlessAgentApplicationService(runtime))) {
            StableProtocolCodec codec = new StableProtocolCodec();
            var init = codec.objectNode().put("token", token.reveal()).put("version", "1.0");
            init.putArray("features").add("RUN").add("DAEMON");
            handler.receive(codec.encode(request("initialize", "init-" + repeat, 1, Optional.empty(), init)));
            List<ProtocolEnvelope> initOutput = drainUntil(handler, codec, "initialized");
            var payload = codec.objectNode().put("prompt", "stable production run");
            ProtocolEnvelope run = request("run.start", "run-" + repeat, 2,
                    replay ? Optional.of("idem-" + repeat) : Optional.empty(), payload);
            handler.receive(codec.encode(run));
            List<ProtocolEnvelope> first = drainUntil(handler, codec, "run.terminal");
            long terminals = first.stream().filter(value -> value.type().equals("run.terminal")).count();
            boolean hasEvent = first.stream().anyMatch(value -> value.kind() == ProtocolMessageKind.EVENT);
            boolean valid = initOutput.stream().anyMatch(value -> value.type().equals("initialized"))
                    && terminals == 1 && hasEvent;
            if (replay) {
                handler.receive(codec.encode(request("run.start", "replay-" + repeat, 3,
                        Optional.of("idem-" + repeat), payload)));
                List<ProtocolEnvelope> second = drainOne(handler, codec);
                valid &= second.size() == 1 && second.getFirst().kind() == ProtocolMessageKind.RESPONSE
                        && second.getFirst().correlationId().equals("replay-" + repeat)
                        && first.stream().filter(value -> value.kind() == ProtocolMessageKind.RESPONSE)
                                .anyMatch(value -> value.type().equals(second.getFirst().type()));
            }
            return new Measurement("stable-v1-handler", valid, 1, 0, valid ? 0 : 1,
                    StopReason.COMPLETED.name());
        }
    }

    private HeadlessRuntimeSession session(String seed, int repeat, ModelGateway gateway) {
        return session(seed, repeat, workspace(seed, repeat), gateway, SessionOpenRequest.create(),
                store(seed, repeat), Duration.ofSeconds(3));
    }

    private HeadlessRuntimeSession session(String seed, int repeat, Path workspace, ModelGateway gateway,
            SessionOpenRequest open, Path store, Duration timeout) {
        return new HeadlessRuntimeSession(gateway, AgentEventSink.noop(),
                options(workspace, store, timeout, open));
    }

    private HeadlessRuntimeOptions options(Path workspace, Path store, Duration timeout, SessionOpenRequest open) {
        return new HeadlessRuntimeOptions(workspace, "fixture", timeout, PermissionMode.DEFAULT,
                List.of(), open, store);
    }

    private HeadlessRuntimeOptions contextOptions(String seed, int repeat, Path workspace) {
        return new HeadlessRuntimeOptions(workspace, "fixture", Duration.ofSeconds(3), PermissionMode.DEFAULT,
                List.of(), SessionOpenRequest.create(), store(seed, repeat),
                Optional.of(new ContextPreparationConfig(
                        new ContextCapacity("fixture", 4_000, 100, 100), 200, 0, 1_024, 256)));
    }

    private Path workspace(String seed, int repeat) {
        Path value = temp.resolve("ws-" + seed + "-" + repeat);
        try { Files.createDirectories(value); } catch (Exception failure) { throw new IllegalStateException(failure); }
        return value;
    }

    private Path store(String seed, int repeat) { return temp.resolve("store-" + seed + "-" + repeat); }

    private static ModelTurn tools(ToolCall... calls) {
        return new ModelTurn(AssistantMessage.tools(List.of(calls)),
                new ModelTurnMetadata(ModelFinishReason.TOOL_CALLS, Optional.empty(), Optional.empty()));
    }

    private static List<ToolResult> toolResults(ModelRequest request) {
        return request.messages().stream().filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast).map(ToolResultMessage::result).toList();
    }

    private static Optional<ToolResult> latestToolResult(ModelRequest request) {
        List<ToolResult> results = toolResults(request);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getLast());
    }

    private static List<String> userTexts(ModelRequest request) {
        return request.messages().stream().filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast).map(UserMessage::content).toList();
    }

    private static Measurement measured(String route, AgentRunResult result, boolean invariant) {
        boolean success = invariant && result != null;
        return new Measurement(route, success, result == null ? 0 : result.modelTurns(),
                result == null ? 0 : result.toolCalls(), success ? 0 : 1,
                result == null ? "MISSING_RESULT" : result.stopReason().name());
    }

    private static ProtocolEnvelope request(String type, String id, long sequence, Optional<String> idempotency,
            tools.jackson.databind.node.ObjectNode payload) {
        return new ProtocolEnvelope(ProtocolVersion.V1_0, ProtocolMessageKind.REQUEST, type, id,
                "client", Optional.empty(), Optional.empty(), sequence, idempotency, payload);
    }

    private static List<ProtocolEnvelope> drainUntil(StableProtocolHandler handler, StableProtocolCodec codec,
            String terminalType) throws Exception {
        List<ProtocolEnvelope> values = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            Optional<byte[]> output = handler.takeOutput(Duration.ofMillis(200));
            if (output.isEmpty()) continue;
            ProtocolEnvelope value = codec.decode(output.orElseThrow());
            values.add(value);
            if (value.type().equals(terminalType)) break;
        }
        return values;
    }

    private static List<ProtocolEnvelope> drainOne(StableProtocolHandler handler, StableProtocolCodec codec)
            throws Exception {
        Optional<byte[]> output = handler.takeOutput(Duration.ofSeconds(2));
        return output.isEmpty() ? List.of() : List.of(codec.decode(output.orElseThrow()));
    }

    private static String json(io.github.liumaishenjian.ccjava.core.eval.EvalReport report,
            Map<String, SeedTotals> totals) {
        StringBuilder seeds = new StringBuilder();
        totals.forEach((name, total) -> {
            if (!seeds.isEmpty()) seeds.append(",\n");
            seeds.append("    \"").append(name).append("\": {\"runs\": ").append(total.runs)
                    .append(", \"modelTurns\": ").append(total.modelTurns)
                    .append(", \"toolCalls\": ").append(total.toolCalls)
                    .append(", \"violations\": ").append(total.violations)
                    .append(", \"stopReasons\": \"").append(String.join(",", total.stopReasons)).append("\"}");
        });
        return "{\n"
                + "  \"schema\": \"cc-java-s14-unified-eval-v4\",\n"
                + "  \"registeredSeeds\": 12,\n"
                + "  \"actualLocalRunsPerSeed\": 5,\n"
                + "  \"actualLocalRuns\": " + report.runs() + ",\n"
                + "  \"completionRate\": " + report.successRate() + ",\n"
                + "  \"knownUsageRuns\": 0,\n"
                + "  \"cacheEvidence\": \"NOT_MEASURED\",\n"
                + "  \"costEvidence\": \"UNKNOWN\",\n"
                + "  \"realOpenAiCalls\": 0,\n"
                + "  \"anthropicProtocolMockCalls\": 0,\n"
                + "  \"medianWallClockMillis\": " + report.medianElapsed().toMillis() + ",\n"
                + "  \"violations\": " + report.violations() + ",\n"
                + "  \"observedSeeds\": {\n" + seeds + "\n  }\n}\n";
    }

    @FunctionalInterface private interface SeedScenario { Measurement run(int repeat) throws Exception; }
    private record Measurement(String route, boolean success, int modelTurns, int toolCalls, int violations,
            String stopReason) { }
    private static final class SeedTotals {
        private int runs;
        private int modelTurns;
        private int toolCalls;
        private int violations;
        private final List<String> stopReasons = new ArrayList<>();
        private void accept(Measurement value, Duration elapsed) {
            runs++; modelTurns += value.modelTurns(); toolCalls += value.toolCalls();
            violations += value.violations(); stopReasons.add(value.stopReason());
            assertThat(elapsed.isNegative()).isFalse();
        }
    }
}
