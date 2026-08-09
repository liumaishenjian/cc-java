package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.Objects;

/**
 * Skill 激活准备结果。成功只表示 Projection 已准备并提交到 Run Scope，不代表执行了 Tool。
 *
 * @param projection 成功候选
 * @param errorCode 失败分类
 * @since 0.11.0
 */
public record SkillInvocationResult(SkillProjection projection, SkillErrorCode errorCode) {
    /** 保证成功和失败恰有一个。 */
    public SkillInvocationResult {
        if ((projection == null) == (errorCode == null)) {
            throw new IllegalArgumentException("成功和失败必须恰有一个");
        }
    }

    /**
     * 创建成功结果。
     *
     * @param value 已准备的 transient Projection
     * @return 成功结果
     */
    public static SkillInvocationResult success(SkillProjection value) {
        return new SkillInvocationResult(Objects.requireNonNull(value, "value 不能为空"), null);
    }

    /**
     * 创建失败结果。
     *
     * @param code 封闭失败分类
     * @return 失败结果
     */
    public static SkillInvocationResult failure(SkillErrorCode code) {
        return new SkillInvocationResult(null, Objects.requireNonNull(code, "code 不能为空"));
    }

    /**
     * 判断调用是否成功。
     *
     * @return 是否成功准备并提交 Projection
     */
    public boolean succeeded() {
        return projection != null;
    }
}
