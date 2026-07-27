/**
 * 提供 cc-java 的命令行入口、终端适配和依赖装配。
 *
 * <p>该包是应用的 Composition Root，未来负责创建模型、工具、核心 Runtime
 * 与终端组件，并把用户输入和 Agent Event 转换为 CLI 交互。CLI 只负责输入、
 * 展示和装配，不承担 Agent 决策、权限判断或 Tool Call 消息拼接。</p>
 *
 * <p>S01 仅建立模块和包边界，不引入 Picocli、JLine 或 Spring Boot；
 * 可交互与 Print 两种 CLI 入口在 S02 中实现。</p>
 *
 * @since 0.1.0
 */
package io.github.liumaishenjian.ccjava.cli;
