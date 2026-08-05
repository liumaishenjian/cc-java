package io.github.liumaishenjian.ccjava.domain.instructions;

import java.util.Objects;

/**
 * 指令发现期间可安全公开的结构化诊断。
 *
 * <p>本类型禁止异常文本、文件路径、正文和完整摘要，只保留固定 code 与长度桶。</p>
 *
 * @param sourceKind 候选来源层级
 * @param safeSourceId 安全逻辑标识
 * @param code 固定失败分类
 * @param lengthBucket 已量化的长度桶；未知为零
 * @param severity 展示严重级别
 * @since 0.8.0
 */
public record InstructionDiagnostic(
        InstructionSourceKind sourceKind,
        String safeSourceId,
        InstructionDiagnosticCode code,
        int lengthBucket,
        InstructionDiagnosticSeverity severity) {

    /** 校验诊断只含受限投影。 */
    public InstructionDiagnostic {
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind 不能为空");
        safeSourceId = new InstructionCandidate(sourceKind, InstructionScopeKind.WORKSPACE,
                safeSourceId, 0, InstructionActivation.STARTUP).safeSourceId();
        code = Objects.requireNonNull(code, "code 不能为空");
        if (lengthBucket < 0) {
            throw new IllegalArgumentException("lengthBucket 不能为负数");
        }
        severity = Objects.requireNonNull(severity, "severity 不能为空");
    }
}
