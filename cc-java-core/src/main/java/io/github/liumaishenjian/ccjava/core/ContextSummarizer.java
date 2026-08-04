package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.SummaryCandidate;
import io.github.liumaishenjian.ccjava.domain.SummaryRequest;
import java.util.Optional;

/**
 * 把有界历史快照归纳为纯数据候选的 Core Port。
 *
 * <p>该 Port 没有 Tool Registry、Tool Pipeline 或 Runtime 引用，因此实现只能返回数据，
 * 不能发起工具调用或直接修改 Canonical Transcript。失败可抛运行时异常，Coordinator 会把它
 * 转换为不含异常正文的结构化诊断。</p>
 *
 * @since 0.7.0
 */
@FunctionalInterface
public interface ContextSummarizer {

    /**
     * 生成尚未提交的摘要候选。
     *
     * @param request 有界输入、来源 revision、来源 ID 与输出预算
     * @param cancellationToken 可传播给具体摘要 Adapter 的取消令牌
     * @return 空表示没有候选；候选仍须通过 Core Adoption Gate
     */
    Optional<SummaryCandidate> summarize(
            SummaryRequest request,
            CancellationToken cancellationToken);
}
