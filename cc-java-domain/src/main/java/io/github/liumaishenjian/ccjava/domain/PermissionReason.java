package io.github.liumaishenjian.ccjava.domain;

/**
 * Permission Outcome 的稳定、隐私安全原因。
 *
 * <p>原因用于测试、生命周期和终端投影，不携带模型文本、任意 Tool 参数、文件正文或
 * Provider 数据。</p>
 *
 * @since 0.5.0
 */
public enum PermissionReason {
    /** 命中不可被配置或审批覆盖的安全拒绝。 */
    HARD_DENIAL,
    /** 命中显式 DENY 规则。 */
    EXPLICIT_DENY,
    /** PLAN 模式禁止非读取副作用。 */
    PLAN_RESTRICTION,
    /** 命中显式 ASK 规则。 */
    EXPLICIT_ASK,
    /** 命中可信 Startup ALLOW 规则。 */
    EXPLICIT_ALLOW,
    /** 命中当前 Session 的精确 Grant。 */
    SESSION_GRANT,
    /** ACCEPT_EDITS 自动允许可信 Workspace Write。 */
    ACCEPT_EDITS_DEFAULT,
    /** 没有匹配规则时使用 Tool Effect 默认。 */
    EFFECT_DEFAULT,
    /** 相同 scope 已连续拒绝两次，当前调用固定拒绝。 */
    REPEATED_DENIAL,
    /** 用户只允许当前调用。 */
    USER_ALLOW_ONCE,
    /** 用户允许当前调用并写入 Session Grant。 */
    USER_ALLOW_SESSION,
    /** 用户显式拒绝。 */
    USER_DENY,
    /** Approval Surface 异常、关闭或返回非法响应后安全拒绝。 */
    APPROVAL_FAILED_CLOSED,
    /** Permission Policy 评估异常或返回非法结果后安全拒绝。 */
    POLICY_EVALUATION_FAILED_CLOSED,
    /** Permission Hook 在 ASK 收敛前明确拒绝。 */
    HOOK_DENIED,
    /** 可信 Permission Hook 在 ASK 收敛前明确允许。 */
    HOOK_ALLOWED
}
