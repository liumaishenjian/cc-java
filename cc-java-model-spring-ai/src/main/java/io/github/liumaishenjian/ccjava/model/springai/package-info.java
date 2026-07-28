/**
 * 提供核心模型协议与 Spring AI 之间的适配边界。
 *
 * <p>该包负责消息、Tool Schema、流式文本、Tool Call、Usage、结束原因
 * 和 Provider 异常的双向转换。S02 首个实现直接使用 Spring AI 2.0 的
 * Ollama StreamingChatModel；适配器只完成一次模型回合，不能自行执行工具，
 * 也不能接管 {@code cc-java-core} 所拥有的 Agent Loop。</p>
 *
 * <p>Reactor、Spring 和 Ollama 类型不得进入 Domain/Core。框架信号在本包
 * 内排队，并由 Runtime 调用线程串行转换为项目事件；取消只声明客户端订阅
 * 边界，不把它夸大为 Provider 服务端停止保证。</p>
 *
 * @since 0.1.0
 */
package io.github.liumaishenjian.ccjava.model.springai;
