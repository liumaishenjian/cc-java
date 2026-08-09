package io.github.liumaishenjian.ccjava.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.AgentSession;
import io.github.liumaishenjian.ccjava.core.ApprovalHandler;
import io.github.liumaishenjian.ccjava.core.DefaultHardDenialPolicy;
import io.github.liumaishenjian.ccjava.core.DefaultPermissionSelectorResolver;
import io.github.liumaishenjian.ccjava.core.InMemorySessionPermissionState;
import io.github.liumaishenjian.ccjava.core.LifecycleDispatcher;
import io.github.liumaishenjian.ccjava.core.PermissionPolicy;
import io.github.liumaishenjian.ccjava.core.ToolExecutionPipeline;
import io.github.liumaishenjian.ccjava.core.ToolRegistry;
import io.github.liumaishenjian.ccjava.core.plugin.PluginLease;
import io.github.liumaishenjian.ccjava.core.plugin.PluginToolProviderDescriptor;
import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.LifecycleEvent;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResultStatus;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginComponentDescriptor;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginComponentKind;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginFingerprint;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginId;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginManifest;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PluginMcpPipelineIntegrationTest {

    @Test
    void factoryToolTraversesRealPermissionApprovalLifecycleAndPipeline() throws Exception {
        var remote = new PipelineClient();
        Created created = create("alpha", remote);
        var state = new InMemorySessionPermissionState();
        AgentSession session = AgentSession.create(
                new SessionId("session-plugin"), SessionSpec.of("pipeline"));
        var lifecycle = new LifecycleDispatcher(Clock.systemUTC(), AgentEventSink.noop());
        var policy = new PermissionPolicy(PermissionMode.DEFAULT, List.of(),
                new DefaultPermissionSelectorResolver(), new DefaultHardDenialPolicy(), state);
        var approvals = new AtomicInteger();
        ApprovalHandler deny = (invocation, definition, outcome) -> {
            approvals.incrementAndGet();
            return ApprovalResponse.deny();
        };
        var deniedPipeline = new ToolExecutionPipeline(
                new ToolRegistry(created.contribution.tools()), policy, deny, state, lifecycle);
        String name = created.contribution.tools().getFirst().definition().name();

        var denied = deniedPipeline.execute(session, new RunId("run-deny"), 1,
                new ToolCall("call-deny", name, new JsonObject(Map.of())));
        assertThat(denied.status()).isEqualTo(ToolResultStatus.DENIED);
        assertThat(remote.calls).hasValue(0);
        assertThat(approvals).hasValue(1);

        ApprovalHandler allow = (invocation, definition, outcome) -> ApprovalResponse.allowOnce();
        var allowedPipeline = new ToolExecutionPipeline(
                new ToolRegistry(created.contribution.tools()), policy, allow, state, lifecycle);
        var allowed = allowedPipeline.execute(session, new RunId("run-allow"), 1,
                new ToolCall("call-allow", name, new JsonObject(Map.of("q", "x"))));

        assertThat(name).isEqualTo("plugin__alpha__tool-provider__remote__search");
        assertThat(created.contribution.tools().getFirst().definition().source()).isEqualTo(ToolSource.PLUGIN);
        assertThat(allowed.callId()).isEqualTo("call-allow");
        assertThat(allowed.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(allowed.metadata().truncated()).isTrue();
        assertThat(allowed.content().codePointCount(0, allowed.content().length()))
                .isEqualTo(created.contribution.tools().getFirst().definition().maxOutputCharacters());
        assertThat(remote.calls).hasValue(1);
        assertThat(session.events().stream().map(envelope -> envelope.event()))
                .anyMatch(LifecycleEvent.BeforeTool.class::isInstance)
                .anyMatch(LifecycleEvent.PermissionEvaluated.class::isInstance)
                .anyMatch(LifecycleEvent.ApprovalRequested.class::isInstance)
                .anyMatch(LifecycleEvent.AfterTool.class::isInstance);
        created.contribution.close();
    }

    private static Created create(String id, PipelineClient remote) throws Exception {
        McpServerConfig config = new McpServerConfig("primary",
                new McpTransportConfig.Stdio(java.nio.file.Path.of("C:/fake.exe"), List.of(), List.of()),
                List.of(), List.of(), Duration.ofSeconds(1), true);
        var server = new PluginComponentDescriptor(
                PluginComponentKind.MCP_SERVER, "primary", "mcp/primary.json", null, List.of(), null);
        var provider = new PluginComponentDescriptor(
                PluginComponentKind.TOOL_PROVIDER, "remote", "providers/remote.json", "mcp-backed",
                List.of("primary"), McpPluginConfigDigest.compute(List.of(config)));
        PluginId pluginId = new PluginId(id);
        var manifest = new PluginManifest(1, pluginId, "1", null, null, List.of(server, provider));
        var snapshot = new PluginSnapshot(manifest,
                new PluginFingerprint(pluginId, "1", "a".repeat(64), "b".repeat(64)), "a".repeat(32));
        PluginLease lease = new PluginLease() {
            private final AtomicBoolean closed = new AtomicBoolean();
            @Override public PluginSnapshot snapshot() { return snapshot; }
            @Override public void close() { closed.compareAndSet(false, true); }
        };
        var factory = new McpBackedPluginToolProviderFactory(List.of(config), ignored -> remote);
        return new Created(factory.create(new PluginToolProviderDescriptor(snapshot, provider), lease));
    }

    private record Created(io.github.liumaishenjian.ccjava.core.plugin.PluginToolContribution contribution) { }

    private static final class PipelineClient implements McpRemoteClient {
        private final AtomicInteger calls = new AtomicInteger();
        @Override public void initialize() { }
        @Override public List<McpToolDescriptor> listTools() {
            return List.of(new McpToolDescriptor("search", "search", Map.of("type", "object")));
        }
        @Override public McpCallOutcome callTool(String name, Map<String, Object> arguments) {
            calls.incrementAndGet();
            return new McpCallOutcome(false,
                    "x".repeat(ToolExecutionPipeline.ABSOLUTE_MAX_OUTPUT_CHARACTERS + 500));
        }
        @Override public void close() { }
    }
}
