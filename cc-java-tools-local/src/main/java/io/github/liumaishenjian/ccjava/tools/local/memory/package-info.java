/**
 * 提供 S07 M1-M3 文件记忆的本地 Adapter。
 *
 * <p>本包负责 repository-id、默认布局、memory root 真实路径边界、严格 UTF-8、受限
 * frontmatter、M1 摘要保护 mutation、Secret candidate 拒绝、M3 Catalog 扫描、M2 原子持久重建与
 * M5 安全正文加载；当前不提供 AgentRuntime/ModelRequest 接入。该应用层校验不是 OS Sandbox。</p>
 *
 * @since 0.7.0
 */
package io.github.liumaishenjian.ccjava.tools.local.memory;
