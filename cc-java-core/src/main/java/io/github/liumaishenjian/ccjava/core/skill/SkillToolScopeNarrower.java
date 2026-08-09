package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.domain.skill.SkillToolRestriction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 只按 Runtime 可见工具与 Skill allowlist 求交，不读取或缓存 Permission/Grant。
 *
 * @since 0.11.0
 */
public final class SkillToolScopeNarrower {
    /**
     * 计算稳定顺序的两集合交集。
     *
     * @param runtimeVisibleTools 调用时 Runtime 实际发布的工具
     * @param restriction Skill 的可选收窄声明
     * @return 不可变结果；顺序与 Runtime 集合一致
     */
    public List<String> narrow(List<String> runtimeVisibleTools, SkillToolRestriction restriction) {
        List<String> runtime = List.copyOf(runtimeVisibleTools);
        if (!restriction.declared()) return runtime;
        Set<String> allowed = new HashSet<>(restriction.toolNames());
        List<String> result = new ArrayList<>();
        for (String tool : runtime) if (allowed.contains(tool)) result.add(tool);
        return List.copyOf(result);
    }
}
