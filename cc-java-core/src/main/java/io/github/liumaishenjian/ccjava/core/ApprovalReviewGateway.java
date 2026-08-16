package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ApprovalReviewRequest;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewResult;

/**
 * 对最终 ASK 执行一次受控自动审查的 Provider-neutral Port。
 *
 * <p>实现只能返回严格 {@link ApprovalReviewResult}，不能执行 Tool、创建规则或 Session Grant。
 * Adapter 必须传播同一 Run 的取消与剩余 deadline，并把 Provider 原始响应安全收敛为固定失败分类。</p>
 *
 * @since 0.15.0
 */
@FunctionalInterface
public interface ApprovalReviewGateway {

    /**
     * 审查一次已经通过 Permission Policy 与 Hook 的最终 ASK。
     *
     * @param request 有界安全请求
     * @param cancellationToken 当前 Run 的共享取消信号
     * @return 严格 verdict 或固定失败
     */
    ApprovalReviewResult review(
            ApprovalReviewRequest request,
            CancellationToken cancellationToken);
}
