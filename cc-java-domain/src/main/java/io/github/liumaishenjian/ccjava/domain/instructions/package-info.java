/**
 * S08 分层 Instructions 的框架无关安全元数据契约。
 *
 * <p>本包只表达来源、作用域、激活、摘要前缀、诊断与 revision；不保存原始正文、
 * 绝对路径、文件 identity、完整 digest 或 Secret。正文只能留在 Core 的短生命周期
 * 内部加载路径，且不得进入 Session、事件或外部 Surface。</p>
 *
 * @since 0.8.0
 */
package io.github.liumaishenjian.ccjava.domain.instructions;
