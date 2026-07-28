/**
 * 提供 cc-java 的命令行入口、终端适配和依赖装配。
 *
 * <p>该包是应用的 Composition Root，负责创建模型、工具、核心 Runtime
 * 与终端组件，并把用户输入和 Agent Event 转换为 CLI 交互。CLI 只负责输入、
 * 展示和装配，不承担 Agent 决策、权限判断或 Tool Call 消息拼接。</p>
 *
 * <p>S02 使用 Picocli 解析 Interactive/Print 参数，使用 JLine 提供基础 REPL，
 * 并以 {@code CliRuntimeFactory} 隔离正在演进的 Provider 与取消实现。Print 模式
 * 不初始化 JLine，非 TTY 不等待输入；完整 Slash Command、持久历史与 Steering
 * 仍属于 S08。</p>
 *
 * @since 0.1.0
 */
package io.github.liumaishenjian.ccjava.cli;
