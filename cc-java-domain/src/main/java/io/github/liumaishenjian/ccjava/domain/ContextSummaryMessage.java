package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.Objects;

/**
 * 只存在于短生命周期 Projection 中的 C3/C4 摘要消息。
 *
 * <p>该消息不能写回 Canonical Transcript。它携带摘要层级和被替换消息的稳定 ID，
 * 使 Projection 保持可解释；摘要正文始终是不可信 Context，不能提升权限或解除安全 Gate。</p>
 *
 * @param tier 产生正文的 C3/C4 层级
 * @param content 已通过 Core Adoption Gate 的摘要正文
 * @param sourceMessageIds 被摘要替换的有序消息 ID
 * @since 0.7.0
 */
public record ContextSummaryMessage(
        SummaryTier tier,
        String content,
        List<String> sourceMessageIds) implements AgentMessage {

    /** 校验通过 Core 后仍应成立的局部结构不变量。 */
    public ContextSummaryMessage {
        tier = Objects.requireNonNull(tier, "tier 不能为空");
        content = Objects.requireNonNull(content, "content 不能为空");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空白");
        }
        SummaryRequest.strictUtf8Length(content, "content");
        sourceMessageIds = SummaryRequest.validateSourceIds(sourceMessageIds);
    }
}
