package io.github.liumaishenjian.ccjava.tools.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.liumaishenjian.ccjava.core.AgentSession;
import io.github.liumaishenjian.ccjava.core.ApprovalHandler;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.DefaultHardDenialPolicy;
import io.github.liumaishenjian.ccjava.core.DefaultPermissionSelectorResolver;
import io.github.liumaishenjian.ccjava.core.InMemorySessionPermissionState;
import io.github.liumaishenjian.ccjava.core.InMemorySessionStore;
import io.github.liumaishenjian.ccjava.core.LifecycleDispatcher;
import io.github.liumaishenjian.ccjava.core.PermissionPolicy;
import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.UuidAgentIdGenerator;
import io.github.liumaishenjian.ccjava.core.SessionJournal;
import io.github.liumaishenjian.ccjava.core.ToolExecutionPipeline;
import io.github.liumaishenjian.ccjava.core.ToolResolutionReason;
import io.github.liumaishenjian.ccjava.core.ToolRegistry;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessDecision;
import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.PermissionRule;
import io.github.liumaishenjian.ccjava.domain.PermissionRuleSource;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolResult;
import io.github.liumaishenjian.ccjava.domain.ToolResultStatus;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** TOOL-18 针对统一 Pipeline、权限拒绝与 durable 边界的专项证据。 */
class WebSearchPipelineTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void defaultAskDeniedByApprovalMakesZeroHttpRequests() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        try (Fixture fixture = fixture(hits, List.of(),
                (invocation, definition, outcome) -> ApprovalResponse.deny())) {
            ToolResult result = fixture.execute("call-default-deny");

            assertThat(result.status()).isEqualTo(ToolResultStatus.DENIED);
            assertThat(result.callId()).isEqualTo("call-default-deny");
            assertThat(hits).hasValue(0);
            assertThat(fixture.journal.started).hasValue(0);
            assertThat(fixture.journal.completed).hasValue(0);
            assertThat(fixture.finalPermissionEvents()).hasSize(1);
        }
    }

    @Test
    void explicitDenyRuleMakesZeroHttpRequestsWithoutApproval() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicInteger approvals = new AtomicInteger();
        PermissionSelector selector = PermissionSelector.toolWide("web_search", ToolSource.BUILT_IN);
        PermissionRule deny = new PermissionRule(PermissionRuleSource.STARTUP, PermissionDecision.DENY, selector);
        try (Fixture fixture = fixture(hits, List.of(deny), (invocation, definition, outcome) -> {
            approvals.incrementAndGet();
            return ApprovalResponse.allowOnce();
        })) {
            ToolResult result = fixture.execute("call-explicit-deny");

            assertThat(result.status()).isEqualTo(ToolResultStatus.DENIED);
            assertThat(result.callId()).isEqualTo("call-explicit-deny");
            assertThat(hits).hasValue(0);
            assertThat(approvals).hasValue(0);
            assertThat(fixture.journal.started).hasValue(0);
            assertThat(fixture.journal.completed).hasValue(0);
            assertThat(fixture.finalPermissionEvents()).hasSize(1);
        }
    }

    @Test
    void allowedCallUsesHttpAndKeepsCallIdWithUniqueDurableAndFinalEvents() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        try (Fixture fixture = fixture(hits, List.of(),
                (invocation, definition, outcome) -> ApprovalResponse.allowOnce())) {
            ToolResult result = fixture.execute("call-allowed");

            assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
            assertThat(result.callId()).isEqualTo("call-allowed");
            assertThat(hits).hasValue(1);
            assertThat(fixture.journal.started).hasValue(1);
            assertThat(fixture.journal.completed).hasValue(1);
            assertThat(fixture.journal.startedCallId).isEqualTo("call-allowed");
            assertThat(fixture.journal.completedCallId).isEqualTo("call-allowed");
            assertThat(fixture.finalPermissionEvents()).hasSize(1);
            assertThat(fixture.events.envelopes())
                    .filteredOn(envelope -> envelope.event() instanceof LifecycleEvent.AfterTool)
                    .hasSize(1);
        }
    }

    private static Fixture fixture(AtomicInteger hits, List<PermissionRule> rules, ApprovalHandler approvals)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            hits.incrementAndGet();
            byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"safe\"}]}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        var configuration = WebSearchConfiguration.loopbackDevelopment(
                WebSearchProvider.EXA,
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search"),
                Optional.empty(), Duration.ofSeconds(2));
        var client = new HostedMcpWebSearchClient(configuration,
                (request, cancellation) -> NetworkAccessDecision.allow());
        var tool = new WebSearchTool(client);
        var events = new EventRecorder();
        var lifecycle = new LifecycleDispatcher(CLOCK, events);
        var store = new InMemorySessionStore(new UuidAgentIdGenerator(), lifecycle);
        AgentSession session = store.create(SessionSpec.of("web-pipeline"));
        var state = new InMemorySessionPermissionState();
        var policy = new PermissionPolicy(PermissionMode.DEFAULT, rules,
                new DefaultPermissionSelectorResolver(), new DefaultHardDenialPolicy(), state);
        var journal = new RecordingJournal();
        var pipeline = new ToolExecutionPipeline(new ToolRegistry(List.of(tool)), policy, approvals,
                state, lifecycle, journal);
        return new Fixture(server, client, pipeline, session, events, journal);
    }

    private record Fixture(HttpServer server, HostedMcpWebSearchClient client,
            ToolExecutionPipeline pipeline, AgentSession session,
            EventRecorder events, RecordingJournal journal) implements AutoCloseable {
        ToolResult execute(String callId) {
            return pipeline.execute(session, new RunId("run-web-pipeline"), 1,
                    new ToolCall(callId, "web_search", new JsonObject(Map.of("query", "bounded"))),
                    CancellationToken.none());
        }

        List<?> finalPermissionEvents() {
            return events.envelopes().stream()
                    .filter(envelope -> envelope.event() instanceof LifecycleEvent.PermissionDecided)
                    .toList();
        }

        @Override public void close() {
            client.close();
            server.stop(0);
        }
    }

    private static final class EventRecorder implements AgentEventSink {
        private final java.util.ArrayList<io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope> envelopes =
                new java.util.ArrayList<>();

        @Override public void publish(io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope envelope) {
            envelopes.add(envelope);
        }

        List<io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope> envelopes() {
            return List.copyOf(envelopes);
        }
    }

    private static final class RecordingJournal implements SessionJournal {
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger completed = new AtomicInteger();
        private String startedCallId;
        private String completedCallId;

        @Override public void runStarted(SessionId sessionId, RunId runId, UserMessage message) { }
        @Override public void assistantAppended(SessionId sessionId, RunId runId, AssistantMessage message) { }
        @Override public void toolResolved(SessionId sessionId, RunId runId, int ordinal,
                ToolResult result, ToolResolutionReason reason) { }
        @Override public void toolStarted(SessionId sessionId, RunId runId, int ordinal,
                String callId, String toolName, ToolEffect effect) {
            started.incrementAndGet();
            startedCallId = callId;
        }
        @Override public void toolCompleted(SessionId sessionId, RunId runId, int ordinal, ToolResult result) {
            completed.incrementAndGet();
            completedCallId = result.callId();
        }
        @Override public void runCompleted(SessionId sessionId, RunId runId, StopReason stopReason) { }
    }
}
