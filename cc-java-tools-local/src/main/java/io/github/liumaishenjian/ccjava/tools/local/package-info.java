/**
 * 提供受 Workspace 与权限边界约束的本地工具适配器。
 *
 * <p>该包未来负责文件读取与搜索、Git 状态与差异、受控文本修改以及进程执行。
 * 所有工具都必须实现核心层定义的契约，并通过统一 Tool Execution Pipeline
 * 执行；本模块不能向 CLI 或模型适配器暴露绕过 Pipeline 的副作用入口。</p>
 *
 * <p>S01 仅建立模块和包边界，不实现文件、Git 或命令工具。只读工具从 S03
 * 开始实现，写入和命令工具从 S04 开始实现。</p>
 *
 * @since 0.1.0
 */
package io.github.liumaishenjian.ccjava.tools.local;
