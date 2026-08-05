/**
 * 提供 Java Headless 入口共用的 Runtime 装配和单 Session 生命周期。
 *
 * <p>该包是 CLI 适配层，不拥有 Agent Loop，也不把 Picocli、stdio 或终端类型
 * 泄漏到 Core。一次性 Print 和持续 stdio 连接都必须通过这里进入同一个
 * {@code AgentRuntime}。S08 G3-C2a 在此包内以不可变 RuntimeScope 将下一 Run 的模型、
 * builtin Tool、Policy、Pipeline 与 Runtime 一次性替换；不实现 Settings 读取、覆盖或用户命令。</p>
 *
 * @since 0.1.0
 */
package io.github.liumaishenjian.ccjava.cli.runtime;
