package io.github.liumaishenjian.ccjava.core;

/**
 * 描述 Tool 未进入 execute 阶段即产生确定性 Result 的固定原因。
 *
 * <p>该值进入 durable journal，不能携带模型参数、审批正文或异常文本。</p>
 *
 * @since 0.6.0
 */
public enum ToolResolutionReason {

    /** Registry 中没有对应 Tool。 */
    UNKNOWN_TOOL,

    /** Tool 参数未通过确定性校验。 */
    INVALID_ARGUMENTS,

    /** Permission 或 Approval 最终拒绝。 */
    PERMISSION_DENIED,

    /** Pre Tool Hook 在 Permission 前明确阻断。 */
    HOOK_BLOCKED,

    /** Plan 尚未批准、步骤未领取或摘要冲突。 */
    PLAN_GATE_BLOCKED,

    /** 当前 Run 的 Skill allowlist 隐藏了该 Tool。 */
    SKILL_SCOPE_DENIED
}
