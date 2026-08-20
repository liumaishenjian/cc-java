package io.github.liumaishenjian.ccjava.tools.web;

/** Web 搜索 Adapter 的封闭失败分类。 */
public enum WebSearchFailure {
    /** 配置默认关闭。 */ DISABLED,
    /** 应用层网络策略拒绝。 */ NETWORK_DENIED,
    /** 当前路径无法由 NetworkAccessPort 完整控制。 */ NETWORK_UNCONTROLLED,
    /** 固定 endpoint 与授权目标不一致。 */ INVALID_TARGET,
    /** 服务端要求重定向，但第一切片禁止跟随。 */ REDIRECT_REFUSED,
    /** 服务端返回 403，禁止原样重试。 */ FORBIDDEN,
    /** 服务端返回 429。 */ RATE_LIMITED,
    /** 服务端返回其他 4xx。 */ REMOTE_CLIENT_ERROR,
    /** 服务端返回 5xx。 */ REMOTE_SERVER_ERROR,
    /** hosted MCP 返回 JSON-RPC error。 */ REMOTE_PROTOCOL_ERROR,
    /** 响应 Content-Type 不是允许的 JSON 或 SSE media type。 */ UNSUPPORTED_MEDIA_TYPE,
    /** 响应不是受支持的严格 JSON-RPC JSON/SSE。 */ MALFORMED_RESPONSE,
    /** hosted MCP 没有返回可用 textual content。 */ NO_RESULTS,
    /** HTTP/SSE/JSON 响应超过硬上限。 */ RESPONSE_TOO_LARGE,
    /** Tool 或 HTTP 期限耗尽。 */ TIMED_OUT,
    /** 当前 Run 已取消。 */ CANCELLED,
    /** 无法归入上述分类的安全失败。 */ EXECUTION_FAILED
}
