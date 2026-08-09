package io.github.liumaishenjian.ccjava.mcp;

import java.util.List;

/**
 * MCP Prompt 的元数据投影；Prompt 正文仍需用户显式选择后获取。
 *
 * @param name 远端 Prompt 名称
 * @param description 可为空字符串的说明
 * @param arguments 只包含参数名的有界元数据
 */
public record McpPromptDescriptor(String name, String description, List<String> arguments) {
    /** 校验名称并冻结参数列表。 */
    public McpPromptDescriptor {
        name = McpServerConfig.requireName(name, "name");
        description = description == null ? "" : description;
        arguments = List.copyOf(arguments == null ? List.of() : arguments);
    }
}
