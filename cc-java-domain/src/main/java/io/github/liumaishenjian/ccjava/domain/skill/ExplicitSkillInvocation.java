package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.Objects;

/**
 * Surface 将 {@code /skill-name [args]} 解析后附加到新 Run 的显式 Skill 意图。
 *
 * <p>该值尚不含 Run ID；Runtime 生成 Run 后才转换为 {@link SkillInvocationRequest}。参数只是
 * 有界不可信文本，不具有 Shell、路径或 Permission 语义，也不得在终态事件中原样回显。</p>
 *
 * @param skillId 目标 Skill
 * @param arguments 有界调用参数
 * @since 0.11.0
 */
public record ExplicitSkillInvocation(SkillId skillId, String arguments) {
    /** 校验 Skill 身份与参数预算。 */
    public ExplicitSkillInvocation {
        skillId = Objects.requireNonNull(skillId, "skillId 不能为空");
        arguments = arguments == null ? "" : arguments;
        if (arguments.codePointCount(0, arguments.length()) > 8_192) {
            throw new IllegalArgumentException("Skill arguments 超限");
        }
    }

    /** @return 不回显参数正文的安全摘要 */
    @Override public String toString() {
        return "ExplicitSkillInvocation[skillId=" + skillId.value() + ", argumentCodePoints="
                + arguments.codePointCount(0, arguments.length()) + "]";
    }
}
