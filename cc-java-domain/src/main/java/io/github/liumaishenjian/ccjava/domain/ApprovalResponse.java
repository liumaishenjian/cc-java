package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 用户或非交互 Surface 对一次 ASK 的类型化响应。
 *
 * <p>ALLOW_ONCE 只覆盖当前 Call ID；ALLOW_SESSION 必须携带与已展示调用完全一致的
 * 具体 scope，由 Core 校验后写入当前内存 Session；DENY 不执行 Tool。</p>
 *
 * @param action 响应动作
 * @param scope 仅 ALLOW_SESSION 必须携带的具体范围
 * @since 0.5.0
 */
public record ApprovalResponse(Action action, Optional<PermissionSelector> scope) {

    /** 审批动作。 */
    public enum Action {
        /** 仅允许当前 Tool Call。 */
        ALLOW_ONCE,
        /** 允许本次调用并写入当前 Session 的精确 Grant。 */
        ALLOW_SESSION,
        /** 拒绝当前 Tool Call。 */
        DENY
    }

    /** 校验动作与 scope 一致性。 */
    public ApprovalResponse {
        action = Objects.requireNonNull(action, "action 不能为空");
        scope = Objects.requireNonNull(scope, "scope 不能为空");
        if ((action == Action.ALLOW_SESSION) != scope.isPresent()) {
            throw new IllegalArgumentException("只有 ALLOW_SESSION 必须携带 scope");
        }
        if (scope.filter(PermissionSelector::toolWide)
                .filter(value -> !(value.toolName().equals("web_search")
                        && value.source() == ToolSource.BUILT_IN))
                .isPresent()) {
            throw new IllegalArgumentException("Session approval scope 必须具体");
        }
    }

    /**
     * 创建只允许当前 Call 的响应。
     *
     * @return 不携带 Session scope 的响应
     */
    public static ApprovalResponse allowOnce() {
        return new ApprovalResponse(Action.ALLOW_ONCE, Optional.empty());
    }

    /**
     * 创建允许匹配 scope 的当前 Session 响应。
     *
     * @param scope 与已展示调用完全一致的具体范围
     * @return 携带精确 Session scope 的响应
     */
    public static ApprovalResponse allowSession(PermissionSelector scope) {
        return new ApprovalResponse(Action.ALLOW_SESSION, Optional.of(scope));
    }

    /**
     * 创建拒绝响应。
     *
     * @return 不携带 scope 的拒绝响应
     */
    public static ApprovalResponse deny() {
        return new ApprovalResponse(Action.DENY, Optional.empty());
    }
}
