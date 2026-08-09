/**
 * 协调 S11 Plugin immutable registry、fingerprint trust、snapshot lease 与受限宿主 Provider SPI。
 *
 * <p>Core 不解析 JSON、不依赖 Path/MCP SDK，也不允许 Provider 绕过 ToolRegistry 与统一 Pipeline。</p>
 *
 * @since 0.11.0
 */
package io.github.liumaishenjian.ccjava.core.plugin;
