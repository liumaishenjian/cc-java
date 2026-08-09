package io.github.liumaishenjian.ccjava.domain.skill;

import io.github.liumaishenjian.ccjava.domain.RunId;
import java.util.Objects;

/**
 * 显式或模型入口解析后交给共同 {@code SkillInvoker} 的类型化调用意图。
 *
 * <p>参数只作为有界文本投影，不具有 Shell 或路径语义。</p>
 *
 * @param runId 当前 Run
 * @param skillId 目标 Skill
 * @param kind 调用入口
 * @param arguments 有界参数文本
 * @since 0.11.0
 */
public record SkillInvocationRequest(RunId runId, SkillId skillId, SkillInvocationKind kind, String arguments) {
    /** 校验身份、入口和参数上限。 */
    public SkillInvocationRequest {
        runId = Objects.requireNonNull(runId, "runId 不能为空");
        skillId = Objects.requireNonNull(skillId, "skillId 不能为空");
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        arguments = arguments == null ? "" : arguments;
        if (arguments.codePointCount(0, arguments.length()) > 8192) {
            throw new IllegalArgumentException("arguments 超限");
        }
    }

    /**
     * 调用参数属于用户/模型不可信文本，诊断不得输出其内容。
     *
     * @return 仅含 Run、Skill、入口和参数长度的摘要
     */
    @Override
    public String toString() {
        return "SkillInvocationRequest[runId=" + runId.value()
                + ", skillId=" + skillId.value()
                + ", kind=" + kind
                + ", argumentCodePoints=" + arguments.codePointCount(0, arguments.length()) + "]";
    }
}
