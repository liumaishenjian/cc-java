/**
 * 本地文本快照、有界行范围读取与 Read 证据登记。
 *
 * <p>本包只负责“把 Workspace 内的普通 UTF-8 文件安全地变成模型可用文本”这一件事：
 * 规范化换行、保留原始字节外观、按 1-based 行范围有界流式读取，以及记录一次 Read
 * 覆盖了哪些行、当时文件身份如何。它不解析模型路径、不判断权限、不写文件，
 * 也不依赖 Provider、Spring、Reactor 或任何持久化框架类型。</p>
 *
 * <p>安全边界仍由 {@code workspace} 包的 WorkspaceGuard 负责；本包所有入口都要求
 * 调用方传入已经通过真实路径与敏感策略校验的目标。</p>
 *
 * @since 0.8.0
 */
package io.github.liumaishenjian.ccjava.tools.local.text;
