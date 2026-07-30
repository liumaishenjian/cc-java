package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ToolOutputStream;

/**
 * Tool 执行期间的有界输出出口。
 *
 * <p>该端口只负责观察，不参与 Tool 决策。实现必须快速返回；观察者失败不能改变
 * 子进程执行结果。最终模型可见内容仍由 {@link ToolExecutionOutcome} 提供。</p>
 *
 * @since 0.4.0
 */
@FunctionalInterface
public interface ToolOutputSink {

    /**
     * 发布一段 stdout 或 stderr。
     *
     * @param stream 输出通道
     * @param text 非空有界片段
     */
    void publish(ToolOutputStream stream, String text);

    /**
     * 返回丢弃所有片段的 Sink。
     *
     * @return 无副作用 Sink
     */
    static ToolOutputSink none() {
        return (ignoredStream, ignoredText) -> {
        };
    }
}
