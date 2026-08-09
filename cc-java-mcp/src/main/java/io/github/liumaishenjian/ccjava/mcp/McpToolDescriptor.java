package io.github.liumaishenjian.ccjava.mcp;

import java.util.Map;
import java.util.Objects;

/**
 * MCP Tool 的 SDK 无关发现投影。
 *
 * @param name 远端 Tool 原始名称
 * @param description 供模型选择 Tool 的说明
 * @param inputSchema SDK 无关 JSON Schema 对象
 */
public record McpToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
    /** 校验名称并冻结 Schema 顶层映射。 */
    public McpToolDescriptor {
        name = McpServerConfig.requireName(name, "name");
        description = description == null || description.isBlank() ? "MCP tool " + name : description;
        inputSchema = Map.copyOf(Objects.requireNonNull(inputSchema, "inputSchema 不能为空"));
    }
}
