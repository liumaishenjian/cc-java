/**
 * 加载并校验模型适配器所需的本地配置。
 *
 * <p>该包只负责把 Git 忽略的本地配置文件和可选环境变量转换为类型化配置，
 * 不创建 Spring AI Client，也不调用 Provider。配置对象的字符串表示必须脱敏，
 * 调用者同样不得把 API Key 写入日志、事件或异常。</p>
 *
 * @since 0.1.0
 */
package io.github.liumaishenjian.ccjava.model.springai.config;
