package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.Objects;

/**
 * Catalog 扫描中不携带物理路径或正文的结构化诊断。
 *
 * @param skillId 能安全解析时的逻辑身份；root 级或非法名称诊断可为空
 * @param code 固定失败分类
 * @since 0.11.0
 */
public record SkillDiagnostic(SkillId skillId, SkillErrorCode code) {
    /** 校验诊断必须具有封闭错误分类。 */
    public SkillDiagnostic {
        code = Objects.requireNonNull(code, "code 不能为空");
    }
}
