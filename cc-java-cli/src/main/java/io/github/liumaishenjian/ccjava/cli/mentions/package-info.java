/**
 * 显式文件提及的 CLI Adapter 边界。
 *
 * <p>该包负责把用户正文中的 {@code @file} 令牌解析为受既有 WorkspaceGuard 约束的不可变
 * UTF-8 快照，并为补全提供只服务 UX 的有界候选。它属于架构边缘：路径解析、真实路径校验、
 * 链接与敏感策略、字节与行数预算都在这里完成，Domain 只接收已经验证的值对象。</p>
 *
 * <p>本包不拥有 Agent 决策。附件正文始终是不可信模型上下文，不能扩大 Tool 权限、Workspace
 * 边界或解除 Permission/Recovery Gate；候选也不构成授权，提交时必须重新做权威校验。任何
 * 显式提及失败都在创建 Run、写入 Session 或请求模型之前以固定安全 code 拒绝。</p>
 *
 * @since 0.8.1
 */
package io.github.liumaishenjian.ccjava.cli.mentions;
