/**
 * 定义 S11 Skill Catalog、调用、内容、资源、投影与恢复校验使用的框架无关协议。
 *
 * <p>这些类型只表达不可变身份、来源、触发策略、内容摘要和结构化失败；不读取文件、
 * 不执行脚本、不决定权限，也不驱动 Agent Loop。Skill 只能收窄运行时可见工具，真实
 * Tool Call 仍由 S05 Permission、Approval 与统一 Pipeline 逐次处理。</p>
 *
 * @since 0.11.0
 */
package io.github.liumaishenjian.ccjava.domain.skill;
