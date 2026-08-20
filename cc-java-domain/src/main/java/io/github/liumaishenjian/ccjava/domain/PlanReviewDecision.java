package io.github.liumaishenjian.ccjava.domain;

/**
 * 用户对精确 durable Plan revision 作出的封闭决定。
 *
 * <p>批准决定同时携带后续 ASK 的审查策略；反馈与拒绝不授予任何权限。</p>
 *
 * @since 0.1.0
 */
public enum PlanReviewDecision {
    /** 批准并将后续 ASK 交给受限 AutoReview。 */
    APPROVE_AUTO,
    /** 批准但保留普通逐 Tool 用户审批。 */
    APPROVE_USER,
    /** 返回同一 Plan revision 链继续规划。 */
    CONTINUE_PLANNING,
    /** 拒绝并关闭当前 Plan。 */
    REJECT
}
