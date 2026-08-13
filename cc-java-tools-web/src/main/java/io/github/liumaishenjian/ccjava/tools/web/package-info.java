/**
 * 提供固定 hosted MCP Provider 目标的受控 Web 搜索边缘适配器。
 *
 * <p>本包只实现 JDK HTTP、JSON-RPC 2.0 {@code tools/call}、严格有界 JSON/SSE 和
 * {@code AgentTool}；模型不能控制 endpoint、Header 或凭证，所有实际出站必须先经过
 * {@code NetworkAccessPort}。返回值保持 external/untrusted provenance，不抓取结果网页，也不把
 * 应用层网络授权描述为 OS Sandbox。</p>
 *
 * @since 0.1.0
 */
package io.github.liumaishenjian.ccjava.tools.web;
