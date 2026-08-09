package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.domain.skill.SkillErrorCode;
import java.util.Objects;

/** Adapter Port 用固定分类报告加载失败，不携带路径或原始异常。 @since 0.11.0 */
public final class SkillLoadingException extends RuntimeException {
    private final SkillErrorCode code;
    public SkillLoadingException(SkillErrorCode code) {
        super("Skill loading failed: " + Objects.requireNonNull(code, "code 不能为空").name());
        this.code = code;
    }
    public SkillErrorCode code() { return code; }
}
