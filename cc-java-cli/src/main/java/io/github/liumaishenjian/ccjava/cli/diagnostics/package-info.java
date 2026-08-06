/**
 * 提供与 Session、stdio 和用户事件隔离的本机模型诊断 Adapter。
 *
 * <p>该包只序列化 Domain 已封闭的诊断字段，并以有界队列、文件轮转和私有权限实现
 * best-effort 持久化；任何失败都不得改变 Agent Run。</p>
 */
package io.github.liumaishenjian.ccjava.cli.diagnostics;
