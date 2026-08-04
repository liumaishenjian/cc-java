package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ContextReductionOutcome;
import io.github.liumaishenjian.ccjava.domain.ProjectionRequest;

/**
 * 规划并执行一次确定性的 Context Projection Reduction。
 *
 * <p>Planner 持有策略选择与“预算满足立即停止”的 Core 决策权；TUI、Provider Adapter
 * 和模型都不得决定是否压缩 Canonical Transcript。</p>
 *
 * @since 0.7.0
 */
@FunctionalInterface
public interface ContextProjectionPlanner {

    /**
     * 从不可变规范消息快照构建有界 Projection。
     *
     * @param request Projection 请求及活动保护边界
     * @param cancellationToken 提交候选前检查的取消令牌
     * @return 唯一结构化终态
     */
    ContextReductionOutcome plan(
            ProjectionRequest request,
            CancellationToken cancellationToken);
}
