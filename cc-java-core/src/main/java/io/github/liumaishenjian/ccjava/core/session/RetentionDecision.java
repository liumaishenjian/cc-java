package io.github.liumaishenjian.ccjava.core.session;

/**
 * 根据 canonical lifecycle 与实际 writer/fence 事实计算的 retention 结果。
 *
 * @param allowed 当前事实是否允许执行请求动作
 * @param action 经评估的 archive/delete 动作
 * @param reason 允许或拒绝的封闭原因
 */
public record RetentionDecision(
        boolean allowed,
        RetentionAction action,
        RetentionReason reason) {
}
