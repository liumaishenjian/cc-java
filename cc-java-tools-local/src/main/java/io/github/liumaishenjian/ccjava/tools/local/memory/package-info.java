/**
 * 提供 S07 M1-M3 文件记忆的本地只读 Adapter。
 *
 * <p>本包负责 repository-id、默认布局、memory root 真实路径边界、严格 UTF-8、受限
 * frontmatter、M3 Catalog 扫描与 M2 索引渲染；当前不提供保存、更新、删除、Secret
 * candidate、M4/M5 或 AgentRuntime 接入。该应用层校验不是 OS Sandbox。</p>
 *
 * @since 0.7.0
 */
package io.github.liumaishenjian.ccjava.tools.local.memory;
