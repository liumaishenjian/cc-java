package io.github.liumaishenjian.ccjava.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.core.plugin.PluginLease;
import io.github.liumaishenjian.ccjava.core.plugin.PluginToolProviderDescriptor;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginComponentDescriptor;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginComponentKind;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginFingerprint;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginId;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginManifest;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class McpBackedPluginToolProviderFactoryTest {

    @Test
    void hostCreatesPluginSourcedQualifiedToolAndClosesClientBeforeLease() throws Exception {
        var client = new FakeClient();
        var closes = new java.util.ArrayList<String>();
        client.closeAction = () -> closes.add("client");
        var fixture = fixture(closes, client);
        var contribution = fixture.factory.create(fixture.descriptor, fixture.lease);

        assertThat(contribution.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.definition().name())
                    .isEqualTo("plugin__alpha__tool-provider__remote__search");
            assertThat(tool.definition().source()).isEqualTo(ToolSource.PLUGIN);
            ToolExecutionOutcome outcome = tool.execute(new ToolInvocation(
                    new SessionId("session-1"), new RunId("run-1"), 1,
                    new ToolCall("call-1", tool.definition().name(), new JsonObject(Map.of("q", "x"))),
                    CancellationToken.none(), (stream, content) -> { }));
            assertThat(outcome.content()).isEqualTo("result");
            assertThat(client.lastRemoteName).isEqualTo("search");
        });

        contribution.close();
        contribution.close();
        assertThat(closes).containsExactly("client", "lease");
        assertThat(client.closes).hasValue(1);
    }

    @Test
    void rejectsLeaseMismatchAndUndeclaredOrUntrustedServer() {
        var client = new FakeClient();
        var fixture = fixture(new java.util.ArrayList<>(), client);
        var otherServer = new PluginComponentDescriptor(
                PluginComponentKind.MCP_SERVER, "primary", "mcp/primary.json", null, List.of(), null);
        PluginSnapshot other = snapshot("other", otherServer, fixture.descriptor.component());
        PluginLease otherLease = lease(other, new java.util.ArrayList<>());
        assertThatThrownBy(() -> fixture.factory.create(fixture.descriptor, otherLease))
                .isInstanceOf(IllegalArgumentException.class);
        otherLease.close();

        var untrustedCloses = new java.util.ArrayList<String>();
        PluginLease untrustedLease = lease(fixture.descriptor.snapshot(), untrustedCloses);
        var untrusted = new McpBackedPluginToolProviderFactory(
                List.of(config(false)), ignored -> client);
        assertThatThrownBy(() -> untrusted.create(fixture.descriptor, untrustedLease))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(untrustedCloses).containsExactly("lease");
    }

    @Test
    void canonicalConfigDigestIsOrderStableAndAnyConfigDriftCreatesNoClient() {
        McpServerConfig primary = config(true);
        McpServerConfig secondary = new McpServerConfig("secondary",
                new McpTransportConfig.StreamableHttp(
                        java.net.URI.create("https://example.invalid/private"), java.util.Optional.of("SECRET_ENV")),
                List.of("search"), List.of("blocked"), Duration.ofSeconds(2), true);
        assertThat(McpPluginConfigDigest.compute(List.of(primary, secondary)))
                .isEqualTo(McpPluginConfigDigest.compute(List.of(secondary, primary)));
        assertThat(McpPluginConfigDigest.compute(List.of(primary)))
                .isNotEqualTo(McpPluginConfigDigest.compute(List.of(new McpServerConfig(
                        "primary", primary.transport(), List.of("search"), List.of(),
                        primary.requestTimeout(), true))));

        var creates = new AtomicInteger();
        var closes = new java.util.ArrayList<String>();
        var server = new PluginComponentDescriptor(
                PluginComponentKind.MCP_SERVER, "primary", "mcp/primary.json", null, List.of(), null);
        var provider = new PluginComponentDescriptor(
                PluginComponentKind.TOOL_PROVIDER, "remote", "providers/remote.json", "mcp-backed",
                List.of("primary"), "0".repeat(64));
        PluginSnapshot snapshot = snapshot("alpha", server, provider);
        PluginLease lease = lease(snapshot, closes);
        var factory = new McpBackedPluginToolProviderFactory(List.of(primary), ignored -> {
            creates.incrementAndGet();
            return new FakeClient();
        });

        assertThatThrownBy(() -> factory.create(new PluginToolProviderDescriptor(snapshot, provider), lease))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Plugin MCP Provider 配置拒绝")
                .hasMessageNotContaining("example.invalid")
                .hasMessageNotContaining("fake.exe")
                .hasMessageNotContaining("SECRET_ENV");
        assertThat(creates).hasValue(0);
        assertThat(closes).containsExactly("lease");
    }

    @Test
    void everyEarlyAndClientStageFailureReleasesLeaseAndCreatedClientsReverse() {
        var closes = new java.util.ArrayList<String>();
        var first = new FakeClient();
        first.closeAction = () -> closes.add("client-1");
        var second = new FakeClient();
        second.initializeFailure = true;
        second.closeAction = () -> closes.add("client-2");
        McpServerConfig primary = config(true);
        McpServerConfig secondary = new McpServerConfig("secondary", primary.transport(),
                List.of(), List.of(), Duration.ofSeconds(1), true);
        var server1 = new PluginComponentDescriptor(
                PluginComponentKind.MCP_SERVER, "primary", "mcp/primary.json", null, List.of(), null);
        var server2 = new PluginComponentDescriptor(
                PluginComponentKind.MCP_SERVER, "secondary", "mcp/secondary.json", null, List.of(), null);
        var provider = new PluginComponentDescriptor(
                PluginComponentKind.TOOL_PROVIDER, "remote", "providers/remote.json", "mcp-backed",
                List.of("primary", "secondary"), McpPluginConfigDigest.compute(List.of(primary, secondary)));
        PluginSnapshot snapshot = snapshot("alpha", server1, server2, provider);
        PluginLease lease = lease(snapshot, closes);
        var index = new AtomicInteger();
        var factory = new McpBackedPluginToolProviderFactory(List.of(primary, secondary),
                ignored -> index.getAndIncrement() == 0 ? first : second);

        assertThatThrownBy(() -> factory.create(new PluginToolProviderDescriptor(snapshot, provider), lease))
                .isInstanceOf(RuntimeException.class);
        assertThat(closes).containsExactly("client-2", "client-1", "lease");
    }

    private static Fixture fixture(List<String> closes, FakeClient client) {
        var server = new PluginComponentDescriptor(
                PluginComponentKind.MCP_SERVER, "primary", "mcp/primary.json", null, List.of(), null);
        var provider = new PluginComponentDescriptor(
                PluginComponentKind.TOOL_PROVIDER, "remote", "providers/remote.json",
                "mcp-backed", List.of("primary"), McpPluginConfigDigest.compute(List.of(config(true))));
        PluginSnapshot snapshot = snapshot("alpha", server, provider);
        PluginLease lease = lease(snapshot, closes);
        return new Fixture(
                new McpBackedPluginToolProviderFactory(List.of(config(true)), ignored -> client),
                new PluginToolProviderDescriptor(snapshot, provider), lease);
    }

    private static PluginSnapshot snapshot(String id, PluginComponentDescriptor... components) {
        var pluginId = new PluginId(id);
        var manifest = new PluginManifest(1, pluginId, "1", null, null, List.of(components));
        return new PluginSnapshot(manifest,
                new PluginFingerprint(pluginId, "1", "b".repeat(64), "c".repeat(64)), "b".repeat(32));
    }

    private static PluginLease lease(PluginSnapshot snapshot, List<String> closes) {
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        return new PluginLease() {
            @Override public PluginSnapshot snapshot() { return snapshot; }
            @Override public void close() { if (closed.compareAndSet(false, true)) closes.add("lease"); }
        };
    }

    private static McpServerConfig config(boolean trusted) {
        return new McpServerConfig("primary",
                new McpTransportConfig.Stdio(java.nio.file.Path.of("C:/fake.exe"), List.of(), List.of()),
                List.of(), List.of(), Duration.ofSeconds(1), trusted);
    }

    private record Fixture(McpBackedPluginToolProviderFactory factory,
            PluginToolProviderDescriptor descriptor, PluginLease lease) { }

    private static final class FakeClient implements McpRemoteClient {
        private final AtomicInteger closes = new AtomicInteger();
        private String lastRemoteName;
        private Runnable closeAction = () -> { };
        private boolean initializeFailure;
        @Override public void initialize() {
            if (initializeFailure) throw new IllegalStateException("initialize failed");
        }
        @Override public List<McpToolDescriptor> listTools() {
            return List.of(new McpToolDescriptor("search", "search", Map.of("type", "object")));
        }
        @Override public McpCallOutcome callTool(String name, Map<String, Object> arguments) {
            lastRemoteName = name;
            return new McpCallOutcome(false, "result");
        }
        @Override public void close() { closes.incrementAndGet(); closeAction.run(); }
    }
}
