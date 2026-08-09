package io.github.liumaishenjian.ccjava.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP SDK 的最小同步 Port，供 Manager、Fake 和官方 SDK Adapter 共同使用。
 *
 * @since 0.10.0
 */
public interface McpRemoteClient extends AutoCloseable {
    /** 完成 initialize 生命周期握手。 */
    void initialize();
    /**
     * 返回分页完成后的 Tool 快照。
     *
     * @return SDK 无关 Tool 描述列表
     */
    List<McpToolDescriptor> listTools();
    /**
     * 调用远端原始 Tool 名。
     *
     * @param name 远端 Tool 原始名称
     * @param arguments 已验证的 JSON 参数对象
     * @return 脱敏且有界的调用结果
     */
    McpCallOutcome callTool(String name, Map<String, Object> arguments);
    /**
     * 发现 Resource 元数据；Server 不支持时可抛出协议异常。
     *
     * @return 不含正文的 Resource 元数据列表
     */
    default List<McpResourceDescriptor> listResources() { return List.of(); }
    /**
     * 发现 Prompt 元数据；Server 不支持时可抛出协议异常。
     *
     * @return 不含 Prompt 正文的元数据列表
     */
    default List<McpPromptDescriptor> listPrompts() { return List.of(); }
    @Override
    void close();
}
