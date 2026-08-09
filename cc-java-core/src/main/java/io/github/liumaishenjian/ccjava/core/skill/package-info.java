/**
 * 协调 S11 Skill Catalog、懒加载、Run Scope 和恢复校验。
 *
 * <p>Core 只依赖框架无关协议与 Port，不知道文件路径、frontmatter 或 JSON。它不改变
 * Agent Loop 和 Permission；每个真实 Tool Call 仍进入既有 S05 管线。</p>
 *
 * @since 0.11.0
 */
package io.github.liumaishenjian.ccjava.core.skill;
