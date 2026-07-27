/**
 * 提供核心模型协议与 Spring AI 之间的适配边界。
 *
 * <p>该包未来负责消息、Tool Schema、流式文本、Tool Call、Usage、结束原因
 * 和 Provider 异常的双向转换。适配器只完成一次模型回合，不能自行执行工具，
 * 也不能接管 {@code cc-java-core} 所拥有的 Agent Loop。</p>
 *
 * <p>S01 仅建立模块和包边界，不引入 Spring AI 依赖或真实 Provider；
 * 真实模型适配在 S02 中实现。</p>
 *
 * @since 0.1.0
 */
package io.github.liumaishenjian.ccjava.model.springai;
