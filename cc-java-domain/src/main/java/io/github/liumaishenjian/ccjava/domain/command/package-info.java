/**
 * 定义 S08 Session Command 的封闭、隐私安全协议。
 *
 * <p>本包只表达已解码命令、终态结果及白名单投影；不含路径、JSON、文件内容、
 * Provider 对象或任意异常文本。Surface 必须在边界将不可信输入转换为这些契约，
 * Application 层负责执行既有 S05/S06/S07 Gate。</p>
 *
 * @since 0.8.0
 */
package io.github.liumaishenjian.ccjava.domain.command;
