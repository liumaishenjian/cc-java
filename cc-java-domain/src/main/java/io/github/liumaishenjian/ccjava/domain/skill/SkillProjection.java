package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.List;
import java.util.Objects;

/**
 * 正文成功加载后可提交给当前 Run 的短生命周期 Skill 投影。
 *
 * @param arguments 调用方提供的有界不可信参数；只投影为文本，不具有 Shell 展开语义
 * @param content 正文快照
 * @param resources 按需资源
 * @param effectiveVisibleTools 只由 Runtime 可见工具与 Skill allowlist 求交得到
 * @since 0.11.0
 */
public record SkillProjection(String arguments, SkillContentSnapshot content,
        List<SkillResourceSnapshot> resources, List<String> effectiveVisibleTools) {
    /** 固定参数、正文、资源与有效工具集合。 */
    public SkillProjection {
        arguments = Objects.requireNonNull(arguments, "arguments 不能为空");
        if (arguments.codePointCount(0, arguments.length()) > 8192) {
            throw new IllegalArgumentException("arguments 超限");
        }
        content = Objects.requireNonNull(content, "content 不能为空");
        resources = List.copyOf(resources == null ? List.of() : resources);
        effectiveVisibleTools = List.copyOf(effectiveVisibleTools == null ? List.of() : effectiveVisibleTools);
    }

    /**
     * 默认 record 字符串会泄露参数、正文和资源；诊断只暴露身份与集合计数。
     *
     * @return 隐私安全摘要
     */
    @Override
    public String toString() {
        return "SkillProjection[skillId=" + content.skillId().value()
                + ", snapshotId=" + content.snapshotId()
                + ", resources=" + resources.size()
                + ", effectiveVisibleTools=" + effectiveVisibleTools.size() + "]";
    }
}
