package io.github.liumaishenjian.ccjava.core.network;

/** JVM 内出站访问的封闭用途。 */
public enum NetworkPurpose {
    /** 调用配置的模型 Provider。 */
    MODEL_PROVIDER,
    /** 向固定 OpenTelemetry endpoint 导出观测信号。 */
    OTEL_EXPORT,
    /** 从机器管理员来源刷新受管策略。 */
    MANAGED_POLICY,
    /** 调用远程 MCP Server。 */
    MCP_REMOTE,
    /** 调用受信任的远程 Hook。 */
    HOOK_REMOTE
}
