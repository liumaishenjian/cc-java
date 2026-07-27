package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;

/**
 * 为离线测试生成可预测且单调递增的 Session ID 与 Run ID。
 *
 * <p>测试不依赖 UUID 或随机数，因此失败消息、事件关联和请求快照都可以稳定断言。</p>
 */
final class SequentialAgentIdGenerator implements AgentIdGenerator {

    private int sessionSequence;
    private int runSequence;

    @Override
    public SessionId newSessionId() {
        return new SessionId("session-" + ++sessionSequence);
    }

    @Override
    public RunId newRunId() {
        return new RunId("run-" + ++runSequence);
    }
}
