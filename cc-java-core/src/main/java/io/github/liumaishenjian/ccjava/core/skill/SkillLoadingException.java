package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.domain.skill.SkillErrorCode;
import java.util.Objects;

/** Adapter Port 用固定分类报告加载失败，不携带路径或原始异常。 @since 0.11.0 */
public final class SkillLoadingException extends RuntimeException {
    /** 不含路径、正文或原始异常的固定错误码。 */
    private final SkillErrorCode code;
    /**
     * 创建只携带固定分类的 Skill 加载失败。
     *
     * @param code 隐私安全错误码
     */
    public SkillLoadingException(SkillErrorCode code) {
        super("Skill loading failed: " + Objects.requireNonNull(code, "code 不能为空").name());
        this.code = code;
    }
    /**
     * 返回不含路径或正文的固定错误码。
     *
     * @return Skill 加载错误码
     */
    public SkillErrorCode code() { return code; }
}
