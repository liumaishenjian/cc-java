/**
 * 提供受 Workspace 与权限边界约束的本地工具适配器。
 *
 * <p>该包未来负责文件读取与搜索、Git 状态与差异、受控文本修改以及进程执行。
 * 所有工具都必须实现核心层定义的契约，并通过统一 Tool Execution Pipeline
 * 执行；本模块不能向 CLI 或模型适配器暴露绕过 Pipeline 的副作用入口。</p>
 *
 * <p>S03 已实现 WorkspaceGuard、固定敏感路径、根 AGENTS.md、WorkspaceSnapshot 与
 * list/search/read/git status/diff 五个只读工具。S04 已加入精确上下文
 * {@code apply_patch} 与只创建新文件的 {@code write_file}；二者共享同一 Guard 并必须
 * 经过 Permission/Approval。通用命令仍未注册。</p>
 *
 * @since 0.1.0
 */
package io.github.liumaishenjian.ccjava.tools.local;
