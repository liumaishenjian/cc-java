package io.github.liumaishenjian.ccjava.domain.instructions;

import java.util.Objects;

/**
 * 已由 Adapter 验证边界后交给 Core 加载的逻辑指令候选。
 *
 * <p>{@code safeSourceId} 只允许用户固定标识或 Workspace 相对标识，不能包含绝对路径、
 * 反斜杠、父级跳转或正文。{@code precedence} 越小越早进入投影。</p>
 *
 * @param sourceKind 候选来源层级
 * @param scopeKind 生效范围
 * @param safeSourceId 不泄露物理路径的受限来源标识
 * @param precedence 低到高合并顺序
 * @param activation 发现触发方式
 * @since 0.8.0
 */
public record InstructionCandidate(
        InstructionSourceKind sourceKind,
        InstructionScopeKind scopeKind,
        String safeSourceId,
        int precedence,
        InstructionActivation activation) {

    /** 校验候选的安全标识与稳定排序字段。 */
    public InstructionCandidate {
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind 不能为空");
        scopeKind = Objects.requireNonNull(scopeKind, "scopeKind 不能为空");
        safeSourceId = requireSafeSourceId(safeSourceId);
        if (precedence < 0) {
            throw new IllegalArgumentException("precedence 不能为负数");
        }
        activation = Objects.requireNonNull(activation, "activation 不能为空");
    }

    private static String requireSafeSourceId(String value) {
        value = Objects.requireNonNull(value, "safeSourceId 不能为空");
        if (value.isBlank() || value.length() > 256 || value.startsWith("/")
                || value.contains("\\") || value.contains("..") || value.contains(":")) {
            throw new IllegalArgumentException("safeSourceId 必须是受限逻辑标识");
        }
        return value;
    }
}
