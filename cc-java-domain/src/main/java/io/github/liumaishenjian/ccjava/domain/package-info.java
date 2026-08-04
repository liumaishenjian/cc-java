/**
 * 定义 Coding Agent Runtime 使用的框架无关领域协议和值对象。
 *
 * <p>该包负责表达 Session、Run、消息、模型回合、工具调用、Context Projection、
 * 文件记忆 Catalog、权限决策、运行限制和终止状态等稳定语义。领域类型应保持不可变，并且不得依赖
 * Spring AI、Reactor、终端、文件系统、持久化或具体模型 Provider。</p>
 *
 * <p>本包只定义跨模块共享的业务语义，不负责驱动 Agent Loop，也不执行
 * 任何外部副作用。上层模块若需要引入框架类型，必须在适配器边界完成转换，
 * 不能把框架对象反向泄漏到这里。</p>
 *
 * @since 0.1.0
 */
package io.github.liumaishenjian.ccjava.domain;
