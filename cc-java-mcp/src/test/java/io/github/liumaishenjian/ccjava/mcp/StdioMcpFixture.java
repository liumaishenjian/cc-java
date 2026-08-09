package io.github.liumaishenjian.ccjava.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 仅供 E2E 测试使用的最小 JSON-RPC MCP STDIO Server。 */
public final class StdioMcpFixture {
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private StdioMcpFixture() {
    }

    public static void main(String[] args) throws Exception {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonNode request = JSON.readTree(line);
                JsonNode id = request.get("id");
                if (id == null) {
                    continue;
                }
                ObjectNode response = JSON.createObjectNode();
                response.put("jsonrpc", "2.0");
                response.set("id", id);
                response.set("result", result(request.path("method").asText(), request));
                System.out.println(JSON.writeValueAsString(response));
                System.out.flush();
            }
        }
    }

    private static ObjectNode result(String method, JsonNode request) {
        ObjectNode result = JSON.createObjectNode();
        switch (method) {
            case "initialize" -> {
                String requested = request.path("params").path("protocolVersion").asText("2025-06-18");
                result.put("protocolVersion", requested);
                result.set("capabilities", JSON.createObjectNode().set(
                        "tools", JSON.createObjectNode().put("listChanged", false)));
                ((ObjectNode) result.get("capabilities"))
                        .set("resources", JSON.createObjectNode().put("listChanged", false));
                ((ObjectNode) result.get("capabilities"))
                        .set("prompts", JSON.createObjectNode().put("listChanged", false));
                result.set("serverInfo", JSON.createObjectNode()
                        .put("name", "cc-java-e2e-fixture")
                        .put("version", "1"));
            }
            case "tools/list" -> {
                ObjectNode tool = JSON.createObjectNode()
                        .put("name", "echo")
                        .put("description", "Echo a value");
                tool.set("inputSchema", JSON.createObjectNode()
                        .put("type", "object")
                        .set("properties", JSON.createObjectNode().set(
                                "value", JSON.createObjectNode().put("type", "string"))));
                result.set("tools", JSON.createArrayNode().add(tool));
            }
            case "tools/call" -> {
                String value = request.path("params").path("arguments").path("value").asText();
                ArrayNode content = JSON.createArrayNode();
                content.add(JSON.createObjectNode().put("type", "text").put("text", "echo:" + value));
                result.set("content", content);
                result.put("isError", false);
            }
            case "resources/list" -> result.set("resources", JSON.createArrayNode().add(
                    JSON.createObjectNode().put("uri", "test://resource").put("name", "fixture-resource")
                            .put("mimeType", "text/plain")));
            case "prompts/list" -> result.set("prompts", JSON.createArrayNode().add(
                    JSON.createObjectNode().put("name", "fixture-prompt").put("description", "Prompt")));
            default -> {
            }
        }
        return result;
    }
}
