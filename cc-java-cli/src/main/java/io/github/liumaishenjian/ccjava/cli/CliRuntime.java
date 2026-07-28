package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.SessionId;

/**
 * 向 Interactive 与 Print 暴露同一个进程内 Agent Session。
 *
 * <p>实现通常包装 {@code AgentRuntime + SessionStore}。该端口不允许终端直接读取
 * Runtime 私有状态；所有进度由创建 Runtime 时提供的 {@link CliEventListener}
 * 观察。</p>
 *
 * @since 0.1.0
 */
public interface CliRuntime extends AutoCloseable {

    /**
     * 返回本进程内连续 Session 的 ID。
     *
     * @return Session ID
     */
    SessionId sessionId();

    /**
     * 在同一 Session 中执行一条用户消息。
     *
     * @param userMessage  用户输入
     * @param cancellationToken 本次 Run 的只读取消信号
     * @return Runtime 唯一终态
     */
    AgentRunResult run(String userMessage, CancellationToken cancellationToken);

    /**
     * 关闭进程内 Session 及 Provider 资源。
     */
    @Override
    void close();
}
