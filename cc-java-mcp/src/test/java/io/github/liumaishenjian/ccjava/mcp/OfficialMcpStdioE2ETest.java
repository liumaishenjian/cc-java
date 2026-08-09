package io.github.liumaishenjian.ccjava.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 真实子进程 + 官方 SDK 的 MCP initialize/discover/call/close E2E。 */
class OfficialMcpStdioE2ETest {

    @Test
    void initializesDiscoversAndCallsOverRealStdioProcess() {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        McpServerConfig config = new McpServerConfig(
                "fixture",
                new McpTransportConfig.Stdio(
                        java,
                        List.of("-cp", System.getProperty("java.class.path"), StdioMcpFixture.class.getName()),
                        List.of()),
                List.of(),
                List.of(),
                Duration.ofSeconds(5),
                true);

        try (McpRemoteClient client = new OfficialMcpClientFactory(Map.of(),
                new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier().get()).create(config)) {
            client.initialize();
            assertThat(client.listTools()).extracting(McpToolDescriptor::name).containsExactly("echo");
            assertThat(client.listResources()).extracting(McpResourceDescriptor::uri)
                    .containsExactly("test://resource");
            assertThat(client.listPrompts()).extracting(McpPromptDescriptor::name)
                    .containsExactly("fixture-prompt");
            assertThat(client.callTool("echo", Map.of("value", "hello")))
                    .isEqualTo(new McpCallOutcome(false, "echo:hello"));
        }
    }
}
