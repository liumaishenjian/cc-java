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
    /** 校验来源层级与非敏感内容身份。 */
    public PolicyProvenance {
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        identity = Objects.requireNonNull(identity, "identity 不能为空");
        if (identity.isBlank() || identity.length() > 128) {
            throw new IllegalArgumentException("identity 无效");
        }
    }

    /** 从高到低的策略所有者种类。 */
    public enum Kind {
        /** 由机器管理员固定且低优先级来源不可放宽。 */
        MANAGED,
        /** 由宿主 Composition Root 固定。 */
        HOST,
        /** 来自用户级可信设置。 */
        USER,
        /** 来自已通过项目 trust Gate 的设置。 */
        PROJECT,
        /** 当前 Session 临时收窄。 */
        SESSION,
        /** 单次 Tool 请求提出的进一步限制。 */
        TOOL_REQUEST
    }
}
