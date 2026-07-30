package io.github.liumaishenjian.ccjava.tools.local.search;

/**
 * ripgrep JSON Lines 超出资源边界或包含无效 JSON 时抛出的受检异常。
 *
 * <p>异常消息只描述协议问题，不回显原始行，防止把路径或源码片段带入日志和模型上下文。</p>
 *
 * @since 0.3.1
 */
public final class RipgrepJsonParseException extends Exception {

    /**
     * 创建安全的协议异常。
     *
     * @param message 不包含原始进程输出的安全消息
     */
    public RipgrepJsonParseException(String message) {
        super(message);
    }
}
