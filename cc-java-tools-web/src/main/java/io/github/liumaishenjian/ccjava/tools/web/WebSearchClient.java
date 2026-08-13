package io.github.liumaishenjian.ccjava.tools.web;

import io.github.liumaishenjian.ccjava.core.CancellationToken;

/**
 * 固定 Search backend 的可替换边缘端口。
 *
 * <p>实现必须在每次出站前执行 NetworkAccessPort，遵守同一取消与 deadline，不得抓取结果 URL。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface WebSearchClient {
    /**
     * 执行一次受控搜索。
     *
     * @param request 已规范化请求
     * @param cancellation 当前 Run 取消令牌
     * @return 有界结构化结果
     * @throws WebSearchException typed 安全失败
     */
    WebSearchResponse search(WebSearchRequest request, CancellationToken cancellation)
            throws WebSearchException;
}
