package io.github.liumaishenjian.ccjava.mcp;

import java.util.List;

/**
 * 单 Server 的 Resource/Prompt 发现快照。
 *
 * @param serverName 稳定 Server 名称
 * @param resources 已发现的 Resource 元数据
 * @param prompts 已发现的 Prompt 元数据
 * @param resourcesAvailable Resource primitive 是否成功响应
 * @param promptsAvailable Prompt primitive 是否成功响应
 */
public record McpContextCatalog(
        String serverName,
        List<McpResourceDescriptor> resources,
        List<McpPromptDescriptor> prompts,
        boolean resourcesAvailable,
        boolean promptsAvailable) {
    /** 冻结两个元数据列表。 */
    public McpContextCatalog {
        resources = List.copyOf(resources);
        prompts = List.copyOf(prompts);
    }
}
