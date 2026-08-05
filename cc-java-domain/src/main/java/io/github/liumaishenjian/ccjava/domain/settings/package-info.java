/**
 * S08 Settings 的框架无关不可变来源声明、诊断与 provenance 契约。
 *
 * <p>本包只保存经过完整校验的有界声明和隐私安全元数据；不读取文件、不解析 JSON、
 * 不执行跨来源合并或 last-known-good 发布，也不把 Settings 参与 S05 权限决策。
 * Settings 正文、密钥、端点、物理路径、完整 selector、指令和 Tool 配置不得出现在诊断或
 * {@code toString()} 中。</p>
 *
 * @since 0.8.0
 */
package io.github.liumaishenjian.ccjava.domain.settings;
