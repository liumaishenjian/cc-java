package io.github.liumaishenjian.ccjava.domain.skill;

/**
 * Skill 激活准备结果。成功只表示 Projection 可提交，不代表已执行任何 Tool。
 *
 * @param projection 成功候选
 * @param errorCode 失败分类
 * @since 0.11.0
 */
public record SkillInvocationResult(SkillProjection projection, SkillErrorCode errorCode) {
    public SkillInvocationResult {
        if ((projection == null) == (errorCode == null)) throw new IllegalArgumentException("成功和失败必须恰有一个");
    }
    public static SkillInvocationResult success(SkillProjection value) { return new SkillInvocationResult(value, null); }
    public static SkillInvocationResult failure(SkillErrorCode code) { return new SkillInvocationResult(null, code); }
    public boolean succeeded() { return projection != null; }
}
