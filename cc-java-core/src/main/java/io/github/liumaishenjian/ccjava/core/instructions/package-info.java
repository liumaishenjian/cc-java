/**
 * S08 分层 Instructions 的纯协调用例与 Adapter Port。
 *
 * <p>本包不访问文件系统、Git、JSON、终端或 Session JSONL。Adapter 必须先完成真实路径、
 * 编码、身份稳定性与 Gitignore 验证；本包只按稳定顺序、限额、去重和取消约束构造原子结果。</p>
 *
 * @since 0.8.0
 */
package io.github.liumaishenjian.ccjava.core.instructions;
