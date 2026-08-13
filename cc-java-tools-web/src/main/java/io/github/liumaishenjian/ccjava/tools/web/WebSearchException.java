package io.github.liumaishenjian.ccjava.tools.web;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * 不携带 endpoint、query、credential、响应正文或底层异常 message 的搜索失败。
 *
 * @since 0.1.0
 */
public final class WebSearchException extends Exception {
    /** 不含不可信文本的封闭失败分类。 */
    private final WebSearchFailure failure;
    /** 仅限 0 到 300 秒的安全重试提示。 */
    private final OptionalLong retryAfterSeconds;

    /**
     * 创建无 Retry-After 的固定失败。
     *
     * @param failure 封闭失败分类
     */
    public WebSearchException(WebSearchFailure failure) {
        this(failure, OptionalLong.empty());
    }

    /**
     * 创建可带安全 Retry-After 秒数的固定失败。
     *
     * @param failure 封闭失败分类
     * @param retryAfterSeconds 已限制的重试提示
     */
    public WebSearchException(WebSearchFailure failure, OptionalLong retryAfterSeconds) {
        super(Objects.requireNonNull(failure, "failure 不能为空").name());
        this.failure = failure;
        this.retryAfterSeconds = Objects.requireNonNull(retryAfterSeconds, "retryAfterSeconds 不能为空");
    }

    /**
     * 返回固定失败分类。
     *
     * @return 固定失败分类
     */
    public WebSearchFailure failure() { return failure; }

    /**
     * 返回已限制的 Retry-After 秒数。
     *
     * @return 已限制的 Retry-After 秒数
     */
    public OptionalLong retryAfterSeconds() { return retryAfterSeconds; }
}
