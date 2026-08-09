package io.github.liumaishenjian.ccjava.mcp;

/**
 * 表示远端 MCP Session 已明确失效，允许 Manager 重建 initialize 会话。
 *
 * <p>只有 Transport 能证明 Session 不再有效时才应使用该异常。普通 Tool 业务失败、
 * 协议错误、取消、超时或未知连接异常都不得映射为该类型，以免重复执行可能已有副作用的
 * Tool Call。</p>
 *
 * @since 0.10.0
 */
public final class McpSessionInvalidException extends RuntimeException {

    /**
     * 创建不携带远端响应正文的 Session 失效异常。
     *
     * @param cause SDK 的结构化 Session 失效异常
     */
    public McpSessionInvalidException(Throwable cause) {
        super("MCP Session 已失效", cause);
    }
}
