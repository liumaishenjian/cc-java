/**
 * 提供固定 User/Project Skill roots 的安全文件系统 Adapter。
 *
 * <p>本包负责严格 frontmatter metadata 扫描、NOFOLLOW containment、UTF-8/数量/大小预算、
 * digest 与 TOCTOU 重检以及正文/资源懒加载；不执行脚本、不注册 Plugin，也不接入最终 CLI/TUI。</p>
 *
 * @since 0.11.0
 */
package io.github.liumaishenjian.ccjava.cli.skills;
