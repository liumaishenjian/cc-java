package io.github.liumaishenjian.ccjava.tools.web;

/**
 * 由可信本地配置选择的托管 Web Search Provider。
 *
 * <p>Provider 决定固定 MCP 目标和远端 Tool 名称；模型参数不能改变该选择。</p>
 *
 * @since 0.1.0
 */
public enum WebSearchProvider {
    /** Exa 的公开 hosted MCP；没有 API key 也可使用。 */
    EXA("web_search_exa"),
    /** Parallel 的 hosted MCP；通常需要本地配置的 API key。 */
    PARALLEL("web_search");

    private final String remoteToolName;

    WebSearchProvider(String remoteToolName) {
        this.remoteToolName = remoteToolName;
    }

    /**
     * 返回 JSON-RPC 调用的远端 Tool 名称。
     *
     * @return {@code tools/call} 使用的固定远端 Tool 名称
     */
    public String remoteToolName() {
        return remoteToolName;
    }
}
