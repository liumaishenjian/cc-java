package io.github.liumaishenjian.ccjava.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 真实 loopback HTTP + 官方 SDK 的 Streamable HTTP E2E。 */
class OfficialMcpHttpE2ETest {
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    void initializesDiscoversAndCallsOverStreamableHttpWithEnvironmentBearer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/mcp", exchange -> handle(exchange, authorization));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
            McpServerConfig config = new McpServerConfig(
                    "http-fixture",
                    new McpTransportConfig.StreamableHttp(endpoint, Optional.of("MCP_TEST_TOKEN")),
                    List.of(),
                    List.of(),
                    Duration.ofSeconds(5),
                    true);
            try (McpRemoteClient client = new OfficialMcpClientFactory(
                    Map.of("MCP_TEST_TOKEN", "secret-value"),
                    new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier().get())
                    .create(config)) {
                client.initialize();
                assertThat(client.listTools()).extracting(McpToolDescriptor::name).containsExactly("echo");
                assertThat(client.callTool("echo", Map.of("value", "http")))
                        .isEqualTo(new McpCallOutcome(false, "echo:http"));
            }
            assertThat(authorization).hasValue("Bearer secret-value");
        } finally {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange, AtomicReference<String> authorization) {
        try (exchange) {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (exchange.getRequestMethod().equals("DELETE")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            JsonNode id = request.get("id");
            if (id == null) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", id);
            response.set("result", result(request));
            byte[] body = JSON.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (Exception failure) {
            try {
                byte[] body = "fixture failure".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception ignored) {
            }
        }
    }

    private static ObjectNode result(JsonNode request) {
        String method = request.path("method").asText();
        ObjectNode result = JSON.createObjectNode();
        if (method.equals("initialize")) {
            result.put("protocolVersion", request.path("params").path("protocolVersion").asText("2025-06-18"));
            result.set("capabilities", JSON.createObjectNode().set(
                    "tools", JSON.createObjectNode().put("listChanged", false)));
            result.set("serverInfo", JSON.createObjectNode().put("name", "http-fixture").put("version", "1"));
        } else if (method.equals("tools/list")) {
            ObjectNode tool = JSON.createObjectNode().put("name", "echo").put("description", "Echo");
            tool.set("inputSchema", JSON.createObjectNode().put("type", "object"));
            result.set("tools", JSON.createArrayNode().add(tool));
        } else if (method.equals("tools/call")) {
            String value = request.path("params").path("arguments").path("value").asText();
            result.set("content", JSON.createArrayNode().add(
                    JSON.createObjectNode().put("type", "text").put("text", "echo:" + value)));
            result.put("isError", false);
        }
        return result;
    }
}
