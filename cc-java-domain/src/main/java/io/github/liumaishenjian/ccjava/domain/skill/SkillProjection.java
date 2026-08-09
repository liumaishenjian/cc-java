package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.List;
import java.util.Objects;

/**
 * 正文成功加载后可提交给当前 Run 的短生命周期 Skill 投影。
 *
 * @param content 正文快照
 * @param resources 按需资源
 * @param effectiveVisibleTools 只由 Runtime 可见工具与 Skill allowlist 求交得到
 * @since 0.11.0
 */
public record SkillProjection(SkillContentSnapshot content, List<SkillResourceSnapshot> resources,
        List<String> effectiveVisibleTools) {
    public SkillProjection {
        content = Objects.requireNonNull(content, "content 不能为空");
        resources = List.copyOf(resources == null ? List.of() : resources);
        effectiveVisibleTools = List.copyOf(effectiveVisibleTools == null ? List.of() : effectiveVisibleTools);
    }
}
