package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.List;
import java.util.Objects;

/**
 * Metadata-only 扫描得到的 Skill 描述，不包含 Markdown 正文或资源内容。
 *
 * @param id 规范身份
 * @param description 单行有界描述
 * @param policy 调用策略
 * @param source 来源
 * @param safeSourceId 不含绝对路径的 Adapter 逻辑句柄
 * @param contentDigest 完整 SKILL.md SHA-256
 * @param toolRestriction 仅用于收窄可见工具的可选声明；显式空列表表示隐藏全部工具
 * @param resources Skill-root-relative 资源名
 * @param hooks 已验证的 Hook 模板逻辑名
 * @since 0.11.0
 */
public record SkillDescriptor(SkillId id, String description, SkillInvocationPolicy policy,
        SkillSource source, String safeSourceId, String contentDigest,
        SkillToolRestriction toolRestriction, List<String> resources, List<String> hooks) {
    /** 校验 metadata 字段、摘要、安全逻辑来源与列表上限。 */
    public SkillDescriptor {
        id = Objects.requireNonNull(id, "id 不能为空");
        description = requireLine(description, 512, "description");
        policy = Objects.requireNonNull(policy, "policy 不能为空");
        source = Objects.requireNonNull(source, "source 不能为空");
        safeSourceId = requireLine(safeSourceId, 512, "safeSourceId");
        if (safeSourceId.startsWith("/") || safeSourceId.contains("\\") || safeSourceId.contains("..") || safeSourceId.contains(":")) {
            throw new IllegalArgumentException("safeSourceId 必须是安全逻辑标识");
        }
        contentDigest = Objects.requireNonNull(contentDigest, "contentDigest 不能为空");
        if (!contentDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("digest 非法");
        toolRestriction = Objects.requireNonNull(toolRestriction, "toolRestriction 不能为空");
        resources = validatedNames(resources, 32, "resources");
        hooks = validatedNames(hooks, 16, "hooks");
    }

    private static List<String> validatedNames(List<String> values, int maximum, String field) {
        List<String> copy = List.copyOf(values == null ? List.of() : values);
        if (copy.size() > maximum
                || copy.stream().anyMatch(value -> value == null || value.isBlank()
                        || !value.equals(value.trim()) || value.contains("\n") || value.contains("\r"))
                || copy.stream().distinct().count() != copy.size()) {
            throw new IllegalArgumentException(field + " 非法或超限");
        }
        return copy;
    }

    private static String requireLine(String value, int max, String field) {
        value = Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank() || value.codePointCount(0, value.length()) > max || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException(field + " 必须是有界单行文本");
        }
        return value;
    }
}
