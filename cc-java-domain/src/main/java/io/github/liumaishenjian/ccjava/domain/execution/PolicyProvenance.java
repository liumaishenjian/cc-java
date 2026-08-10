package io.github.liumaishenjian.ccjava.domain.execution;

import java.util.Objects;

/**
 * 安全策略的可信来源；只携带固定种类和非敏感身份摘要。
 *
 * @param kind 来源层级
 * @param identity 非敏感内容身份
 * @since 0.13.0
 */
public record PolicyProvenance(Kind kind, String identity) {
    public PolicyProvenance {
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        identity = Objects.requireNonNull(identity, "identity 不能为空");
        if (identity.isBlank() || identity.length() > 128) {
            throw new IllegalArgumentException("identity 无效");
        }
    }

    /** 从高到低的策略所有者种类。 */
    public enum Kind {
        MANAGED,
        HOST,
        USER,
        PROJECT,
        SESSION,
        TOOL_REQUEST
    }
}
