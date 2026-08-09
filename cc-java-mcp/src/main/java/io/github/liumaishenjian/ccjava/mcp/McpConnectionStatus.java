package io.github.liumaishenjian.ccjava.mcp;

/** 单 MCP Server 的脱敏连接状态。 */
public enum McpConnectionStatus {
    /** 已配置但尚未 initialize。 */
    PENDING,
    /** initialize 和 Tool 发现已完成。 */
    CONNECTED,
    /** 配置未通过 Trust Gate，未创建 Transport。 */
    UNTRUSTED,
    /** 连接或发现失败，原始异常未进入状态。 */
    FAILED,
    /** Manager 已关闭并释放 Client。 */
    CLOSED
}
