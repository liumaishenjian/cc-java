package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 自动审批审查的严格结果。
 *
 * <p>成功 verdict 只有当前调用允许或拒绝，不提供 Session Grant。Provider、timeout、解析、内部
 * 或取消失败必须以固定分类表达；该类型不允许自由文本理由，以避免 Adapter 原始响应进入
 * Permission 生命周期。</p>
 *
 * @param verdict 严格 verdict；失败时为空
 * @param failure 固定失败分类；成功 verdict 时为空
 * @since 0.15.0
 */
public record ApprovalReviewResult(Optional<Verdict> verdict, Optional<FailureKind> failure) {

    /** 自动审查能产生的唯一成功决定。 */
    public enum Verdict {
        /** 仅允许当前 Call ID。 */
        ALLOW_ONCE,
        /** 拒绝当前 Call ID。 */
        DENY
    }

    /** 失败关闭所需的稳定分类。 */
    public enum FailureKind {
        /** Provider 或 Transport 请求失败。 */
        PROVIDER,
        /** 请求在 Provider 或 Adapter deadline 内未完成。 */
        TIMEOUT,
        /** 模型响应不满足严格 verdict 协议。 */
        PARSE,
        /** Adapter 或 Core 收敛过程发生内部失败。 */
        INTERNAL,
        /** 共享 Run token 已确认取消。 */
        CANCELLED
    }

    /** 要求成功与失败恰好一项存在。 */
    public ApprovalReviewResult {
        verdict = Objects.requireNonNull(verdict, "verdict 不能为空");
        failure = Objects.requireNonNull(failure, "failure 不能为空");
        if (verdict.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException("verdict 与 failure 必须恰好存在一项");
        }
    }

    /**
     * 创建仅允许当前调用的结果。
     *
     * @return 仅允许当前调用的严格结果
     */
    public static ApprovalReviewResult allowOnce() {
        return new ApprovalReviewResult(Optional.of(Verdict.ALLOW_ONCE), Optional.empty());
    }

    /**
     * 创建拒绝当前调用的结果。
     *
     * @return 拒绝当前调用的严格结果
     */
    public static ApprovalReviewResult deny() {
        return new ApprovalReviewResult(Optional.of(Verdict.DENY), Optional.empty());
    }

    /**
     * 创建失败结果。
     *
     * @param kind 固定失败分类
     * @return 不包含 verdict 的失败结果
     */
    public static ApprovalReviewResult failure(FailureKind kind) {
        return new ApprovalReviewResult(Optional.empty(), Optional.of(kind));
    }
}
