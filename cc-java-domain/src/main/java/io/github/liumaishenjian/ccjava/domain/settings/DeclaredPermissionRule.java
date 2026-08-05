package io.github.liumaishenjian.ccjava.domain.settings;

/**
 * 单一 Settings 来源中已经通过结构校验的权限规则声明。
 *
 * <p>该契约只保留后续 Settings 合并所需的定义或删除意图，不把来源 JSON 直接映射为 S05
 * Policy，也不改变既有 Hard Denial、可信 ToolSource 或审批决策。</p>
 *
 * @since 0.8.0
 */
public sealed interface DeclaredPermissionRule
        permits DeclaredPermissionRuleDefinition, DeclaredPermissionRuleRemoval {

    /**
     * 返回用于后续稳定合并的规则标识。
     *
     * @return 已经校验为小写 kebab-case 的规则标识
     */
    String ruleId();
}
