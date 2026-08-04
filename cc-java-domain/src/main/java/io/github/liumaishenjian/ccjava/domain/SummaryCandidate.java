package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.Objects;

/**
 * 摘要 Port 返回的纯数据候选。
 *
 * <p>候选本身不代表已提交。Core 仍须核对 tier、source revision、来源 ID 精确覆盖、请求预算、
 * 事实锚点、Tool 协议污染、Token 降幅与取消状态，全部通过后才能派生新的
 * {@link ContextProjection}。</p>
 *
 * @param tier 候选对应的 C3/C4 层级
 * @param summary 摘要正文
 * @param sourceRevision 摘要器实际处理的来源 revision
 * @param sourceMessageIds 摘要器实际覆盖的有序消息 ID
 * @param utf8Bytes 摘要正文的严格 UTF-8 字节数
 * @param estimatedTokens 摘要正文的估算 Token 数
 * @since 0.7.0
 */
public record SummaryCandidate(
        SummaryTier tier,
        String summary,
        long sourceRevision,
        List<String> sourceMessageIds,
        int utf8Bytes,
        long estimatedTokens) {

    /**
     * 校验候选的局部结构；与具体请求的匹配由 Core Adoption Gate 完成。
     *
     * @throws NullPointerException 必填引用为空时
     * @throws IllegalArgumentException 正文、revision、来源 ID 或统计非法时
     */
    public SummaryCandidate {
        tier = Objects.requireNonNull(tier, "tier 不能为空");
        summary = Objects.requireNonNull(summary, "summary 不能为空");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary 不能为空白");
        }
        int actualBytes = SummaryRequest.strictUtf8Length(summary, "summary");
        if (actualBytes > SummaryRequest.MAX_OUTPUT_UTF8_BYTES) {
            throw new IllegalArgumentException("summary 超过候选 UTF-8 字节硬上限");
        }
        if (utf8Bytes != actualBytes) {
            throw new IllegalArgumentException("utf8Bytes 必须与 summary 严格 UTF-8 字节数一致");
        }
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("sourceRevision 不能为负数");
        }
        sourceMessageIds = SummaryRequest.validateSourceIds(sourceMessageIds);
        if (estimatedTokens < 1 || estimatedTokens > SummaryRequest.MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("estimatedTokens 超出允许范围");
        }
    }
}
