package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * C3/C4 摘要 Gate 的隐私安全结构化诊断。
 *
 * <p>诊断只记录分类和可选层级，不携带 Prompt、摘要正文、Tool 输出、来源 ID 或底层异常文案。</p>
 *
 * @param kind 诊断分类
 * @param tier 关联层级；仅全局“不需要摘要”诊断为空
 * @since 0.7.0
 */
public record SummaryDiagnostic(Kind kind, Optional<SummaryTier> tier) {

    /** 摘要跳过、拒绝或终止的有界原因码。 */
    public enum Kind {
        /** C1/C2 后已满足容量，无需进入模型摘要。 */
        SUMMARY_NOT_REQUIRED,
        /** rolling window 尚不满足 C3 前提。 */
        ROLLING_WINDOW_INELIGIBLE,
        /** C4 的完整边界、锚点或来源前提不完整。 */
        FULL_SUMMARY_PREREQUISITES_MISSING,
        /** 同一 run/source revision 的该层级已尝试过。 */
        ATTEMPT_COOLDOWN,
        /** 所选来源无法构造成受支持的有界快照。 */
        INPUT_LIMIT_EXCEEDED,
        /** 所选来源边界会切断 Tool Call/Result 批次。 */
        SOURCE_BOUNDARY_INVALID,
        /** 候选层级与请求层级不一致。 */
        TIER_MISMATCH,
        /** 候选来源 revision 与请求不一致。 */
        STALE_SOURCE_REVISION,
        /** 候选没有精确覆盖请求中的有序来源消息 ID。 */
        SOURCE_COVERAGE_MISMATCH,
        /** 摘要器没有返回候选。 */
        EMPTY_CANDIDATE,
        /** 候选超过请求 UTF-8 字节上限。 */
        OUTPUT_BYTE_LIMIT_EXCEEDED,
        /** 候选超过请求 Token 上限。 */
        OUTPUT_TOKEN_LIMIT_EXCEEDED,
        /** 候选 Token 估算没有严格低于来源。 */
        NO_TOKEN_REDUCTION,
        /** 候选虽降低占用但仍无法满足 Context 容量。 */
        CAPACITY_STILL_EXCEEDED,
        /** 候选丢失至少一个受保护事实锚点。 */
        PROTECTED_ANCHOR_LOSS,
        /** 候选包含 Tool Call/Result 协议片段。 */
        TOOL_PROTOCOL_CONTAMINATION,
        /** 摘要 Port 失败，异常正文不得进入诊断。 */
        SUMMARIZER_FAILED,
        /** 运行在候选提交前被取消。 */
        CANCELLED
    }

    /**
     * 校验全局诊断与层级诊断的形状。
     *
     * @throws NullPointerException kind、tier 容器或层级为空时
     * @throws IllegalArgumentException 全局原因错误携带层级，或层级原因缺少层级时；取消可发生在层级选择前
     */
    public SummaryDiagnostic {
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        tier = Objects.requireNonNull(tier, "tier 不能为空");
        boolean globalOnly = kind == Kind.SUMMARY_NOT_REQUIRED;
        boolean tierOptional = kind == Kind.CANCELLED;
        if (globalOnly && tier.isPresent()) {
            throw new IllegalArgumentException("全局诊断不能携带 tier");
        }
        if (!globalOnly && !tierOptional && tier.isEmpty()) {
            throw new IllegalArgumentException("层级诊断必须携带 tier");
        }
        if (kind == Kind.ROLLING_WINDOW_INELIGIBLE
                && tier.orElseThrow() != SummaryTier.C3_ROLLING) {
            throw new IllegalArgumentException("ROLLING_WINDOW_INELIGIBLE 只适用于 C3");
        }
        if (kind == Kind.FULL_SUMMARY_PREREQUISITES_MISSING
                && tier.orElseThrow() != SummaryTier.C4_FULL) {
            throw new IllegalArgumentException("FULL_SUMMARY_PREREQUISITES_MISSING 只适用于 C4");
        }
    }

    /**
     * 创建不关联层级的全局诊断。
     *
     * @param kind 尚未选择 C3/C4 tier 时可报告的全局原因
     * @return 不携带 tier 的全局摘要诊断
     */
    public static SummaryDiagnostic global(Kind kind) {
        return new SummaryDiagnostic(kind, Optional.empty());
    }

    /**
     * 创建关联具体 C3/C4 层级的诊断。
     *
     * @param kind 仅能在某一摘要 tier 内判定的原因
     * @param tier 产生或拒绝候选的 C3/C4 tier
     * @return 关联该 tier 的摘要诊断
     */
    public static SummaryDiagnostic tier(Kind kind, SummaryTier tier) {
        return new SummaryDiagnostic(kind, Optional.of(
                Objects.requireNonNull(tier, "tier 不能为空")));
    }
}
