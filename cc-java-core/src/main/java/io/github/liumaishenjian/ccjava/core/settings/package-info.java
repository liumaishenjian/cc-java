/**
 * S08 Settings 的框架无关纯合并服务。
 *
 * <p>本包只处理已验证来源快照的固定优先级、逐字段合并和原子失败结果；不访问文件、JSON、
 * Git、Session JSONL、终端或 S05 Permission Policy。RuntimeSettingsApplier 仅从不可变
 * 基线构造下一 Run 的完整候选，并在失败时原子保留前一配置。</p>
 *
 * @since 0.8.0
 */
package io.github.liumaishenjian.ccjava.core.settings;
