package io.github.liumaishenjian.ccjava.domain.instructions;

import java.util.Objects;

/**
 * 已解析指令的隐私安全来源摘要。
 *
 * @param sourceKind 来源层级
 * @param scopeKind 生效作用域
 * @param safeSourceId 不泄露物理路径的来源标识
 * @param digestPrefix 内容摘要的短前缀，不是完整 digest
 * @param precedence 合并优先级
 * @param activation 发现触发方式
 * @since 0.8.0
 */
public record InstructionProvenance(
        InstructionSourceKind sourceKind,
        InstructionScopeKind scopeKind,
        String safeSourceId,
        String digestPrefix,
        int precedence,
        InstructionActivation activation) {

    /** 校验 provenance 不会成为正文、完整摘要或路径的载体。 */
    public InstructionProvenance {
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind 不能为空");
        scopeKind = Objects.requireNonNull(scopeKind, "scopeKind 不能为空");
        safeSourceId = new InstructionCandidate(sourceKind, scopeKind, safeSourceId, precedence,
                Objects.requireNonNull(activation, "activation 不能为空")).safeSourceId();
        digestPrefix = Objects.requireNonNull(digestPrefix, "digestPrefix 不能为空");
        if (!digestPrefix.matches("[0-9a-f]{8,16}")) {
            throw new IllegalArgumentException("digestPrefix 必须是 8 至 16 位小写十六进制前缀");
        }
    }
}
