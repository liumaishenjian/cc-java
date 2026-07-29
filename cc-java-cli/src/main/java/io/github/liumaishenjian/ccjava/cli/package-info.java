/**
 * 提供 cc-java 的 Java Headless 入口与依赖装配。
 *
 * <p>该包是 Java 应用的 Composition Root，负责创建模型、工具与核心 Runtime，
 * 并通过 Print 或 stdio 协议暴露命令和事件。React/Ink 终端位于独立的
 * {@code cc-java-tui} 包；Java Headless 不承担终端重绘，也不把 Agent 决策、
 * 权限判断或 Tool Call 消息拼接交给 UI。</p>
 *
 * <p>S02 先通过 Fake stdio Spike 验证协议、取消和进程生命周期。协议达到
 * G2 前仅是内部 v0，不是稳定 SDK 或外部兼容承诺。</p>
 *
 * @since 0.1.0
 */
package io.github.liumaishenjian.ccjava.cli;
