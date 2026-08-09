package io.github.liumaishenjian.ccjava.mcp;

/**
 * 不包含端点、argv、Secret 或异常正文的 MCP 诊断快照。
 *
 * @param serverName 稳定 Server 名称
 * @param status 当前脱敏连接状态
 * @param toolCount 已发布 Tool 数量
 */
public record McpServerSnapshot(String serverName, McpConnectionStatus status, int toolCount) {
}
