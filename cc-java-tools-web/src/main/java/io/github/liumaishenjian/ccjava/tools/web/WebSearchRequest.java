package io.github.liumaishenjian.ccjava.tools.web;

import java.util.Objects;

/**
 * 已完成严格规范化、可交给固定 hosted MCP Search Adapter 的查询请求。
 *
 * @param query 不记录到普通观测出口的搜索词
 * @param resultLimit Provider 返回结果的有界提示值
 * @since 0.1.0
 */
public record WebSearchRequest(String query, int resultLimit) {
    /** 校验查询和结果上限；Provider 参数由 Adapter 固定，模型不能覆盖。 */
    public WebSearchRequest {
        query = Objects.requireNonNull(query, "query 不能为空");
        if (query.isBlank() || resultLimit < 1 || resultLimit > 20) {
            throw new IllegalArgumentException("web search request 非法");
        }
    }
}
