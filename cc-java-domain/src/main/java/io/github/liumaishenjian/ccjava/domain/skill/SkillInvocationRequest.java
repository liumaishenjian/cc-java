package io.github.liumaishenjian.ccjava.domain.skill;

import io.github.liumaishenjian.ccjava.domain.RunId;
import java.util.Objects;

/** @param runId 当前 Run @param skillId 目标 Skill @param kind 调用入口 @param arguments 有界参数文本 @since 0.11.0 */
public record SkillInvocationRequest(RunId runId, SkillId skillId, SkillInvocationKind kind, String arguments) {
    public SkillInvocationRequest {
        runId = Objects.requireNonNull(runId, "runId 不能为空");
        skillId = Objects.requireNonNull(skillId, "skillId 不能为空");
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        arguments = arguments == null ? "" : arguments;
        if (arguments.codePointCount(0, arguments.length()) > 8192) throw new IllegalArgumentException("arguments 超限");
    }
}
