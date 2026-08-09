package io.github.liumaishenjian.ccjava.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证 S10 多 Server、Trust、过滤、前缀、失败隔离与关闭契约。 */
class McpClientManagerTest {

    @Test
    void discoversFilteredPrefixedToolsAndIsolatesFailedAndUntrustedServers() {
        AtomicInteger created = new AtomicInteger();
        List<FakeClient> clients = new ArrayList<>();
        McpClientFactory factory = config -> {
            created.incrementAndGet();
            FakeClient client = new FakeClient(config.name());
            clients.add(client);
            return client;
        };
        McpServerConfig alpha = config("alpha", true, List.of("read", "write"), List.of("write"));
        McpServerConfig failed = config("failed", true, List.of(), List.of());
        McpServerConfig untrusted = config("untrusted", false, List.of(), List.of());
        try (McpClientManager manager = new McpClientManager(
                List.of(alpha, failed, untrusted), factory)) {
            List<io.github.liumaishenjian.ccjava.core.AgentTool> tools = manager.start();

            assertThat(tools).extracting(tool -> tool.definition().name())
                    .containsExactly("mcp__alpha__read");
            assertThat(tools.getFirst().definition().source().name()).isEqualTo("MCP");
            assertThat(created).hasValue(2);
            assertThat(manager.snapshots()).containsExactly(
                    new McpServerSnapshot("alpha", McpConnectionStatus.CONNECTED, 1),
                    new McpServerSnapshot("failed", McpConnectionStatus.FAILED, 0),
                    new McpServerSnapshot("untrusted", McpConnectionStatus.UNTRUSTED, 0));
        }
        assertThat(clients).allMatch(FakeClient::closed);
    }

    @Test
    void rejectsDuplicateServersAndUnsafeHttpEndpoints() {
        McpServerConfig duplicate = config("same", true, List.of(), List.of());
        assertThatThrownBy(() -> new McpClientManager(List.of(duplicate, duplicate), ignored -> null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpTransportConfig.StreamableHttp(
                URI.create("http://example.com/mcp"), java.util.Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpTransportConfig.Stdio(
                Path.of("relative-mcp"), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reconnectsAndRetriesToolCallOnlyOnceAfterSessionFailure() throws Exception {
        AtomicInteger generations = new AtomicInteger();
        McpClientFactory factory = ignored -> new McpRemoteClient() {
            private final int generation = generations.incrementAndGet();
            @Override public void initialize() { }
            @Override public List<McpToolDescriptor> listTools() {
                return List.of(new McpToolDescriptor("read", "read", Map.of("type", "object")));
            }
            @Override public McpCallOutcome callTool(String name, Map<String, Object> arguments) {
                if (generation == 1) throw new IllegalStateException("expired");
                return new McpCallOutcome(false, "recovered");
            }
            @Override public void close() { }
        };
        try (McpClientManager manager = new McpClientManager(
                List.of(config("recover", true, List.of(), List.of())), factory)) {
            var tool = manager.start().getFirst();
            var outcome = tool.execute(new io.github.liumaishenjian.ccjava.core.ToolInvocation(
                    new io.github.liumaishenjian.ccjava.domain.SessionId("session"),
                    new io.github.liumaishenjian.ccjava.domain.RunId("run"),
                    1,
                    new io.github.liumaishenjian.ccjava.domain.ToolCall(
                            "call", tool.definition().name(), io.github.liumaishenjian.ccjava.domain.JsonObject.empty())));

            assertThat(outcome.successful()).isTrue();
            assertThat(outcome.content()).isEqualTo("recovered");
            assertThat(generations).hasValue(2);
        }
    }

    private static McpServerConfig config(
            String name, boolean trusted, List<String> allow, List<String> deny) {
        return new McpServerConfig(
                name,
                new McpTransportConfig.Stdio(Path.of("C:\\tools\\mcp.exe"), List.of(), List.of()),
                allow,
                deny,
                Duration.ofSeconds(1),
                trusted);
    }

    private static final class FakeClient implements McpRemoteClient {
        private final String server;
        private boolean closed;

        private FakeClient(String server) {
            this.server = server;
        }

        @Override
        public void initialize() {
            if (server.equals("failed")) {
                throw new IllegalStateException("private failure");
            }
        }

        @Override
        public List<McpToolDescriptor> listTools() {
            return List.of(
                    new McpToolDescriptor("write", "write", Map.of("type", "object")),
                    new McpToolDescriptor("read", "read", Map.of("type", "object")));
        }

        @Override
        public McpCallOutcome callTool(String name, Map<String, Object> arguments) {
            return new McpCallOutcome(false, "ok");
        }

        @Override
        public void close() {
            closed = true;
        }

        private boolean closed() {
            return closed;
        }
    }
}
