package io.github.liumaishenjian.ccjava.domain.settings;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 删除低优先级同标识 Settings 权限规则的声明。
 *
 * <p>删除声明只表达 {@code {"remove":"rule-id"}} 的来源意图；它不携带可被误用为新规则的
 * decision、effect、Tool 或 selector，也不在本切片执行跨来源删除。</p>
 *
 * @param ruleId 要删除的低优先级规则标识
 * @since 0.8.0
 */
public record DeclaredPermissionRuleRemoval(String ruleId) implements DeclaredPermissionRule {
    private static final Pattern RULE_ID = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    /** 校验删除目标是有界的小写 kebab-case 标识。 */
    public DeclaredPermissionRuleRemoval {
        ruleId = Objects.requireNonNull(ruleId, "ruleId 不能为空");
        if (ruleId.length() > 64 || !RULE_ID.matcher(ruleId).matches()) {
            throw new IllegalArgumentException("ruleId 非法");
        }
    }
}
