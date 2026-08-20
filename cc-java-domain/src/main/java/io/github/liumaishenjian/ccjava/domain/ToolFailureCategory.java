package io.github.liumaishenjian.ccjava.domain;

/**
 * 跨 Tool Adapter 的稳定失败治理分类。
 *
 * <p>该分类描述调用为什么没有成功，供 Runtime 决定是否允许原样重试；它不取代更细的
 * {@link ToolErrorCode}。分类不得从自由文本、HTML 或 stderr 猜测，只能由确定性策略、
 * HTTP 状态、进程退出状态或 Adapter 的类型化事实产生。</p>
 *
 * @since 0.15.0
 */
public enum ToolFailureCategory {
    /** 凭证缺失、失效或认证失败。 */ AUTHORIZATION,
    /** 应用 Permission、审批、Hook 或 Hard Denial 拒绝。 */ PERMISSION,
    /** HTTP 403；具体认证、User-Agent/ACL 或普通禁止原因可能不可观察。 */ HTTP_FORBIDDEN,
    /** 除 403、429 外的 HTTP 4xx。 */ HTTP_CLIENT,
    /** HTTP 429。 */ HTTP_RATE_LIMIT,
    /** HTTP 5xx。 */ HTTP_SERVER,
    /** DNS、连接、TLS、断流等传输失败。 */ TRANSPORT,
    /** 子进程已启动但以非零状态退出。 */ PROCESS_EXIT,
    /** 参数、路径或前置条件校验失败。 */ VALIDATION,
    /** Tool 业务执行失败。 */ EXECUTION,
    /** 当前 Run 已取消。 */ CANCELLATION,
    /** Tool 自身墙钟期限耗尽。 */ TIMEOUT,
    /** 输出或响应大小超过上限。 */ OUTPUT_LIMIT,
    /** 外部或内部协议不满足契约。 */ PROTOCOL,
    /** Runtime 无法安全恢复的内部错误。 */ INTERNAL
}
