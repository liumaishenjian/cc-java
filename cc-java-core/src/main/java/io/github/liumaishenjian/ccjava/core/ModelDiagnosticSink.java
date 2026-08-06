package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticEvent;

/**
 * 模型诊断事件的 best-effort 本机出口。
 *
 * <p>实现失败必须由调用侧隔离，不能影响模型流、重试、Run 终态或 Session durable
 * 顺序。该端口不属于 Agent Event 或 SessionJournal。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface ModelDiagnosticSink {

    /**
     * 尝试记录一个封闭事件。
     *
     * @param event 不含任意文本的诊断事件
     */
    void record(ModelDiagnosticEvent event);

    /**
     * 返回不执行任何操作的 sink。
     *
     * @return 共享 no-op sink
     */
    static ModelDiagnosticSink noop() {
        return event -> { };
    }
}
