/**
 * 将 MCP Server 的远程协议能力转换成本项目 {@code AgentTool} 的边缘适配模块。
 *
 * <p>本包可以依赖官方 MCP Java SDK，但不得让 SDK、Reactor 或传输类型进入
 * Domain/Core。发现出的 Tool 仍由 Core 的统一 Permission 与执行管线决定是否执行。</p>
 */
package io.github.liumaishenjian.ccjava.mcp;
