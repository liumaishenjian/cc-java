package io.github.liumaishenjian.ccjava.domain.instructions;

/**
 * 指令候选被纳入发现请求的触发方式。
 *
 * @since 0.8.0
 */
public enum InstructionActivation {
    /** 启动时固定发现的候选。 */
    STARTUP,
    /** 仅由已验证的 Workspace 相对目标触发的候选。 */
    VERIFIED_TARGET
}
