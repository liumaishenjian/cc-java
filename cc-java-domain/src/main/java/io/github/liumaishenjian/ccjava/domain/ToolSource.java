package io.github.liumaishenjian.ccjava.domain;

/**
 * 标识 Tool Definition 的注册来源。
 *
 * <p>来源参与命名冲突诊断和后续信任决策，但不能绕过统一
 * Tool Execution Pipeline。S01 只实际使用 {@link #BUILT_IN}。</p>
 *
 * @since 0.1.0
 */
public enum ToolSource {

    /** Runtime 随发行包提供的内置 Tool。 */
    BUILT_IN,

    /** 由 MCP Server 暴露的 Tool。 */
    MCP,

    /** 由 Plugin 提供的 Tool。 */
    PLUGIN,

    /** 由隔离的 Sub-Agent 暴露的委托 Tool。 */
    SUB_AGENT
}
