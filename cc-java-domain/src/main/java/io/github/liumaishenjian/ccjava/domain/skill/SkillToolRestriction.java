package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.List;

/**
 * 表示 Skill 对当前 Run 工具可见性的可选收窄声明。
 *
 * <p>未声明时保留 Runtime 已发布的工具集合；显式声明空列表时隐藏全部工具；非空列表
 * 只参与集合交集，绝不能增加工具、批准权限或创建 Grant。</p>
 *
 * @param declared frontmatter 是否显式出现 {@code allowed-tools}
 * @param toolNames 规范工具名；仅在已声明时有意义
 * @since 0.11.0
 */
public record SkillToolRestriction(boolean declared, List<String> toolNames) {
    /** 校验声明状态、名称、重复项与列表上限。 */
    public SkillToolRestriction {
        toolNames = List.copyOf(toolNames == null ? List.of() : toolNames);
        if (!declared && !toolNames.isEmpty()) {
            throw new IllegalArgumentException("未声明的工具限制不能携带名称");
        }
        if (toolNames.size() > 32 || toolNames.stream().anyMatch(name -> name == null || name.isBlank()
                || !name.equals(name.trim()) || name.contains("\n") || name.contains("\r"))) {
            throw new IllegalArgumentException("工具限制非法或超过 32 项");
        }
        if (toolNames.stream().distinct().count() != toolNames.size()) {
            throw new IllegalArgumentException("工具限制不能包含重复名称");
        }
    }

    /**
     * 返回不额外收窄 Runtime 工具的缺省声明。
     *
     * @return 未声明限制
     */
    public static SkillToolRestriction unspecified() {
        return new SkillToolRestriction(false, List.of());
    }

    /**
     * 创建显式 allowlist；空列表具有“隐藏全部工具”的语义。
     *
     * @param toolNames allowlist
     * @return 不可变限制
     */
    public static SkillToolRestriction declared(List<String> toolNames) {
        return new SkillToolRestriction(true, toolNames);
    }
}
