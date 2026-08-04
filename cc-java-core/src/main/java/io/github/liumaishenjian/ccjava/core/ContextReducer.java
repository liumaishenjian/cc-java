package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ContextReductionOutcome;
import io.github.liumaishenjian.ccjava.domain.ProjectionRequest;

/**
 * 对 Context Projection 应用安全、可解释的 Reduction。
 *
 * <p>该 G3-A Port 只承诺确定性的 C1/C2。C3/C4 必须在后续切片提供摘要 Port、事实校验和
 * 提交 Gate 后再实现，不能以空摘要或简单截断伪装。</p>
 *
 * @since 0.7.0
 */
@FunctionalInterface
public interface ContextReducer {

    /**
     * 构造候选并在取消、预算和协议 Gate 通过后提交。
     *
     * @param request Projection 请求
     * @param cancellationToken 取消令牌
     * @return Reduction 结构化终态
     */
    ContextReductionOutcome reduce(
            ProjectionRequest request,
            CancellationToken cancellationToken);
}
