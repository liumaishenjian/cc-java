package io.github.liumaishenjian.ccjava.cli.runtime;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Headless Runtime MCP E2E 使用的最小 STDIO Server。 */
public final class RuntimeMcpStdioFixture {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private RuntimeMcpStdioFixture() { }

    public static void main(String[] args) throws Exception {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonNode request = JSON.readTree(line);
                if (!request.has("id")) continue;
                ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
                response.set("id", request.get("id"));
                response.set("result", result(request));
                System.out.println(JSON.writeValueAsString(response));
                System.out.flush();
            }
        }
    }

    private static ObjectNode result(JsonNode request) {
        ObjectNode result = JSON.createObjectNode();
        switch (request.path("method").asText()) {
            case "initialize" -> {
                result.put("protocolVersion", request.path("params").path("protocolVersion").asText("2025-06-18"));
                result.set("capabilities", JSON.createObjectNode().set(
                        "tools", JSON.createObjectNode().put("listChanged", false)));
                result.set("serverInfo", JSON.createObjectNode().put("name", "runtime-fixture").put("version", "1"));
            }
            case "tools/list" -> {
                ObjectNode tool = JSON.createObjectNode().put("name", "echo").put("description", "Echo");
                tool.set("inputSchema", JSON.createObjectNode().put("type", "object"));
                result.set("tools", JSON.createArrayNode().add(tool));
            }
            case "tools/call" -> {
                String value = request.path("params").path("arguments").path("value").asText();
                result.set("content", JSON.createArrayNode().add(
                        JSON.createObjectNode().put("type", "text").put("text", "runtime:" + value)));
                result.put("isError", false);
            }
            default -> { }
        }
        return result;
    }
}
