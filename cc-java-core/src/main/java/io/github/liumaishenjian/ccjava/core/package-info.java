/**
 * 定义并实现 Coding Agent 的核心运行时与对外端口。
 *
 * <p>该包承载显式 Agent Loop、模型端口、工具注册与执行管线、Context Projection、C3/C4 纯数据
 * Summarizer Port、候选 Gate、冷却与单 overflow retry 协调器、Memory Catalog/Repository/Recall/Projection
 * Port 与 ready-only Prefetch、内存 Session、运行限制、停止原因和生命周期事件。核心层负责确定性的
 * 状态迁移与终止判断，模型只能提出工具调用意图，不能绕过核心直接产生
 * 环境副作用。</p>
 *
 * <p>核心层只依赖 {@code cc-java-domain}。它不得依赖 Spring AI、Reactor、
 * Picocli、JLine、文件系统实现、进程执行器或持久化框架；这些能力必须通过
 * Port 由外层 Adapter 提供。</p>
 *
 * @since 0.1.0
 */
package io.github.liumaishenjian.ccjava.core;
