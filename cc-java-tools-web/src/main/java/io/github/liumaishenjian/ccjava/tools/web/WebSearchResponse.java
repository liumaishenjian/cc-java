package io.github.liumaishenjian.ccjava.tools.web;

import java.util.Objects;

/**
 * hosted MCP Search Adapter 返回给 Tool 的有界、不可信 textual content。
 *
 * <p>Hosted MCP 可以返回带引用的自由文本，而不是逐条结构化 URL；本协议保留该事实，
 * 不伪造 result hit，也不暗示 Runtime 已抓取其中链接。</p>
 *
 * @param providerHost 实际固定出站目标的规范 host
 * @param content 已清洗并限制长度的 MCP textual content
 * @param contentItems 聚合的 textual content 项数
 * @param truncated textual content 是否因本地上限被截断
 * @since 0.1.0
 */
public record WebSearchResponse(
        String providerHost,
        String content,
        int contentItems,
        boolean truncated) {
    /** 校验 Adapter 输出的不变量。 */
    public WebSearchResponse {
        providerHost = Objects.requireNonNull(providerHost, "providerHost 不能为空");
        content = Objects.requireNonNull(content, "content 不能为空");
        if (providerHost.isBlank() || content.isBlank() || contentItems < 1) {
            throw new IllegalArgumentException("web search response 非法");
        }
    }
}
