package io.github.liumaishenjian.ccjava.core.skill;

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
    public List<String> narrow(List<String> runtimeVisibleTools, List<String> skillAllowedTools) {
        List<String> runtime = List.copyOf(runtimeVisibleTools);
        if (skillAllowedTools == null || skillAllowedTools.isEmpty()) return runtime;
        Set<String> allowed = new HashSet<>(skillAllowedTools);
        List<String> result = new ArrayList<>();
        for (String tool : runtime) if (allowed.contains(tool)) result.add(tool);
        return List.copyOf(result);
    }
}
