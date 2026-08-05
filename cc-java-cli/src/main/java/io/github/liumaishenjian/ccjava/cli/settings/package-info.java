/**
 * S08 Settings v1 的 CLI 来源解析安全适配层。
 *
 * <p>本包将已读取的有界字节解析为 Domain Settings 声明，并在物化前限制编码、结构和字段；
 * 不读取候选文件、不解析来源优先级、不合并 Settings 或发布 last-known-good 快照。解析失败只
 * 返回固定分类诊断，不能回显 Settings 正文、密钥、端点、路径、selector、指令或 Tool 配置。</p>
 *
 * @since 0.8.0
 */
package io.github.liumaishenjian.ccjava.cli.settings;
