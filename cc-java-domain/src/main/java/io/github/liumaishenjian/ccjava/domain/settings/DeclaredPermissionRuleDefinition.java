package io.github.liumaishenjian.ccjava.domain.settings;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一个完整且尚未接入 S05 Policy 的 Settings 权限规则定义。
 *
 * <p>所有字段作为一个不可分割的规则对象保存，后续来源只能完整替换它，不能部分合并。选择器
 * 属于不可信声明，任何面向用户的诊断都不得回显其内容。</p>
 *
 * @param ruleId 稳定的小写 kebab-case 标识
 * @param decision 候选决策名
 * @param effect 候选 Tool Effect 名
 * @param tool 已注册的 builtin Tool 名
 * @param toolSource 固定可信 ToolSource 名
 * @param selector 已规范校验但不得回显的选择器
 * @since 0.8.0
 */
public record DeclaredPermissionRuleDefinition(
        String ruleId,
        String decision,
        String effect,
        String tool,
        String toolSource,
        String selector) implements DeclaredPermissionRule {
    private static final Pattern RULE_ID = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    /**
     * 校验完整规则的标识与必填字段，不解释 S05 权限语义。
     */
    public DeclaredPermissionRuleDefinition {
        ruleId = checkedRuleId(ruleId);
        decision = required(decision, "decision");
        effect = required(effect, "effect");
        tool = required(tool, "tool");
        toolSource = required(toolSource, "toolSource");
        selector = checkedSelector(selector);
    }

    @Override
    public String toString() {
        return "DeclaredPermissionRuleDefinition[ruleId=" + ruleId + ", decision=" + decision
                + ", effect=" + effect + ", tool=" + tool + ", toolSource=" + toolSource + ", selector=<redacted>]";
    }

    private static String checkedRuleId(String value) {
        value = required(value, "ruleId");
        if (value.length() > 64 || !RULE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("ruleId 非法");
        }
        return value;
    }

    private static String checkedSelector(String value) {
        value = Objects.requireNonNull(value, "selector 不能为空");
        if (value.codePointCount(0, value.length()) > 4_096 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("selector 非法");
        }
        return value;
    }

    private static String required(String value, String name) {
        value = Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value;
    }
}
