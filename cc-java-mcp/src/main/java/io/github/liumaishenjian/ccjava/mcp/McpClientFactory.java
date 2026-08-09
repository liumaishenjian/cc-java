package io.github.liumaishenjian.ccjava.mcp;

/** 创建尚未 initialize 的单 Server Client。 */
@FunctionalInterface
public interface McpClientFactory {
    /**
     * 为一份可信配置创建新 Client，不复用旧协议会话。
     *
     * @param config 已通过来源 Trust Gate 的 Server 配置
     * @return 尚未执行 initialize 的 Client
     */
    McpRemoteClient create(McpServerConfig config);
}
